/*
 * Copyright 2021-2022 ForgeRock AS. All Rights Reserved
 *
 * Use of this code requires a commercial software license with ForgeRock AS.
 * or with one of its affiliates. All use shall be exclusively subject
 * to such license between the licensee and ForgeRock AS.
 */

// postcommit-tests.groovy

import com.forgerock.pipeline.reporting.PipelineRunLegacyAdapter

void runStage(PipelineRunLegacyAdapter pipelineRun, Random random, boolean generateSummaryReport) {

    def stageName = 'POSTCOMMIT-ALL-TESTS'
    def clusterConfig = [:]
    clusterConfig['PROJECT'] = cloud_config.commonConfig()['PROJECT']
    clusterConfig['CLUSTER_DOMAIN'] = 'postcommit-forgeops.engineeringpit.com'
    def scaleClusterConfig = [:]
    scaleClusterConfig['SCALE_CLUSTER'] = ['frontend-pool': 5, 'primary-pool': 20]

    try {
        dockerUtils.insideGoogleCloudImage(dockerfilePath: 'docker/google-cloud', getDockerfile: true) {
            cloud_utils.scaleClusterUp(clusterConfig + scaleClusterConfig)
        }

        // Define group of tests to execute on a GCE VM, we can't run all the tests at the same time
        // otherwise we have lot of timeout issues with the cluster
        // Each group below is executed on a GCE VM
        String[][] stageToCheckbox = [
                [
                        'Postcommit_pit1',
                        'Postcommit_perf_postcommit',
                        'Postcommit_perf_restore',
                        'Postcommit_am_k8s_postcommit',
                        'Postcommit_am_k8s_upgrade',
                        'Postcommit_ds_k8s_postcommit',
                        'Postcommit_ds_k8s_upgrade',
                ],
                [
                        'Postcommit_idm_k8s_postcommit',
                        'Postcommit_idm_k8s_upgrade',
                        'Postcommit_ig_k8s_postcommit',
                        'Postcommit_ig_k8s_upgrade',
                        'Postcommit_platform_ui',
                        'Postcommit_set_images',
                ],
        ]

        // To avoid issue we don't execute the group above at the same time, we wait 5 mins between each
        // Here we calculate the time to wait before executing a group and if a group is emtpy we don't wait
        Integer[] sleepTimesMinutes = new Integer[stageToCheckbox.length]
        int i = 0
        int sleep = 0
        for (String[] checkboxNames: stageToCheckbox) {
            if (checkboxNames.any { elem -> params[elem] }) {
                sleepTimesMinutes[i] = sleep
                sleep += 5
            }
            i++
        }

        parallel(
                'postcommit-set-0': {
                    runPostcommitInNode(0, sleepTimesMinutes) {
                        runPostcommitSet0(pipelineRun, random, clusterConfig)
                    }
                },
                'postcommit-set-1': {
                    runPostcommitInNode(1, sleepTimesMinutes) {
                        runPostcommitSet1(pipelineRun, random, clusterConfig)
                    }
                }
        )
    } catch (Exception exception) {
        println("Exception during parallel stage: ${exception}")
        throw exception
    } finally {
        if (generateSummaryReport) {
            commonLodestarModule.generateSummaryTestReport(stageName)
        }

        dockerUtils.insideGoogleCloudImage(dockerfilePath: 'docker/google-cloud', getDockerfile: true) {
            cloud_utils.scaleClusterDown(clusterConfig + scaleClusterConfig)
        }
    }
}

def runPostcommitInNode(int stageNumber, Integer[] sleepTimeMinutes, Closure runPostcommit) {
    if (sleepTimeMinutes[stageNumber] != null) {
        sleep(time: sleepTimeMinutes[stageNumber], unit: 'MINUTES')

        node('gce-vm-forgeops-n1-standard-32') {
            checkout scm
            sh "git checkout ${commonModule.GIT_COMMIT}"

            runPostcommit()
        }
    } else {
        println("No tests to execute in postcommit-set-${stageNumber}")
    }
}

def runPostcommitSet0(PipelineRunLegacyAdapter pipelineRun, Random random, LinkedHashMap clusterConfig) {
    def parallelTestsMap = [:]

    // **************
    // DEV full tests
    // **************
    if (params.Postcommit_pit1) {
        parallelTestsMap.put('PIT1',
                {
                    commonLodestarModule.runSpyglaas(pipelineRun, random, 'PIT1', clusterConfig +
                            [TESTS_SCOPE: 'tests/pit1']
                    )
                }
        )
    }

    if (params.Postcommit_perf_postcommit) {
        parallelTestsMap.put('Perf Postcommit',
                {
                    commonLodestarModule.runPyrock(pipelineRun, random, 'Perf Postcommit', clusterConfig +
                            [TEST_NAME      : 'postcommit',
                             PROFILE_NAME   : 'small']
                    )
                }
        )
    }

    if (params.Postcommit_perf_restore) {
        parallelTestsMap.put('Perf Restore',
                {
                    commonLodestarModule.runPyrock(pipelineRun, random, 'Perf Restore', clusterConfig +
                            [TEST_NAME                      : 'platform',
                             PROFILE_NAME                   : 'small',
                             CONFIGFILE_NAME                : 'conf-postcommit-restore-100k.yaml',
                             DEPLOYMENT_RESTORE_BUCKET_URL  : 'gs://performance-bucket-us-east1/postcommit/idrepo-100k']
                    )
                }
        )
    }

    // *************
    // DEV k8s tests
    // *************
    if (params.Postcommit_am_k8s_postcommit) {
        parallelTestsMap.put('AM K8s Postcommit',
                {
                    commonLodestarModule.runSpyglaas(pipelineRun, random, 'AM K8s Postcommit', clusterConfig +
                            [TESTS_SCOPE: 'tests/k8s/postcommit/am']
                    )
                }
        )
    }
    if (params.Postcommit_am_k8s_upgrade) {
        parallelTestsMap.put('AM K8s Upgrade',
                {
                    def randomNumber = random.nextInt(99999) + 100000 // 6 digit random number to compute to namespace
                    def upgradeCommonConfig = clusterConfig + [
                            TESTS_SCOPE         : 'tests/k8s/postcommit/am',
                            DEPLOYMENT_NAMESPACE: cloud_config.commonConfig()['DEPLOYMENT_NAMESPACE'] + '-' +
                                    randomNumber,
                    ]

                    def deploymentConfig = upgradeCommonConfig + [
                            REPORT_NAME_PREFIX       : 'am_k8s_upgrade_deployment',
                            SKIP_TESTS               : true,
                            SKIP_CLEANUP             : true,
                            STASH_PLATFORM_IMAGES_REF: commonLodestarModule.fraasProductionTag,
                            STASH_FORGEOPS_REF       : commonLodestarModule.forgeopsFraasProduction,
                    ]

                    def amLatestPromotedTag = commonLodestarModule.getPromotedProductTag(commonModule.DEFAULT_PLATFORM_IMAGES_TAG, 'am')
                    def amLatestPromotedRepo = commonLodestarModule.getPromotedProductRepo(commonModule.DEFAULT_PLATFORM_IMAGES_TAG, 'am')

                    def testConfig = upgradeCommonConfig + [
                            REPORT_NAME_PREFIX                    : 'am_k8s_upgrade_upgrade',
                            SKIP_DEPLOY                           : true,
                            DEPLOYMENT_UPGRADE_FIRST              : true,
                            COMPONENTS_AM_IMAGE_UPGRADE_TAG       : amLatestPromotedTag,
                            COMPONENTS_AM_IMAGE_UPGRADE_REPOSITORY: amLatestPromotedRepo,
                    ]

                    commonLodestarModule.runUpgrade(pipelineRun, random, 'AM K8s Upgrade', deploymentConfig, testConfig)
                }
        )
    }

    if (params.Postcommit_ds_k8s_postcommit) {
        parallelTestsMap.put('DS K8s Postcommit',
                {
                    commonLodestarModule.runSpyglaas(pipelineRun, random, 'DS K8s Postcommit', clusterConfig +
                            [TESTS_SCOPE: 'tests/k8s/postcommit/ds']
                    )
                }
        )
    }
    if (params.Postcommit_ds_k8s_upgrade) {
        parallelTestsMap.put('DS K8s Upgrade',
                {
                    def randomNumber = random.nextInt(99999) + 100000 // 6 digit random number to compute to namespace
                    def upgradeCommonConfig = clusterConfig + [
                            TESTS_SCOPE         : 'tests/k8s/postcommit/ds/standard',
                            DEPLOYMENT_NAMESPACE: cloud_config.commonConfig()['DEPLOYMENT_NAMESPACE'] + '-' +
                                    randomNumber,
                    ]

                    def deploymentConfig = upgradeCommonConfig + [
                            REPORT_NAME_PREFIX       : 'ds_k8s_upgrade_deployment',
                            SKIP_TESTS               : true,
                            SKIP_CLEANUP             : true,
                            STASH_PLATFORM_IMAGES_REF: commonLodestarModule.fraasProductionTag,
                            STASH_FORGEOPS_REF       : commonLodestarModule.forgeopsFraasProduction,
                    ]

                    def dsLatestPromotedTag = commonLodestarModule.getPromotedProductTag(commonModule.DEFAULT_PLATFORM_IMAGES_TAG, 'ds')
                    def dsLatestPromotedRepo = commonLodestarModule.getPromotedProductRepo(commonModule.DEFAULT_PLATFORM_IMAGES_TAG, 'ds')

                    def testConfig = upgradeCommonConfig + [
                            REPORT_NAME_PREFIX                          : 'ds_k8s_upgrade_upgrade',
                            SKIP_DEPLOY                                 : true,
                            DEPLOYMENT_UPGRADE_FIRST                    : true,
                            COMPONENTS_DSIDREPO_IMAGE_UPGRADE_TAG       : dsLatestPromotedTag,
                            COMPONENTS_DSIDREPO_IMAGE_UPGRADE_REPOSITORY: dsLatestPromotedRepo,
                            COMPONENTS_DSCTS_IMAGE_UPGRADE_TAG          : dsLatestPromotedTag,
                            COMPONENTS_DSCTS_IMAGE_UPGRADE_REPOSITORY   : dsLatestPromotedRepo,
                    ]

                    commonLodestarModule.runUpgrade(pipelineRun, random, 'DS K8s Upgrade', deploymentConfig, testConfig)
                }
        )
    }

    parallel parallelTestsMap
}

def runPostcommitSet1(PipelineRunLegacyAdapter pipelineRun, Random random, LinkedHashMap clusterConfig) {
    def parallelTestsMap = [:]

    // *************
    // DEV k8s tests
    // *************
    if (params.Postcommit_idm_k8s_postcommit) {
        parallelTestsMap.put('IDM K8s Postcommit',
                {
                    commonLodestarModule.runSpyglaas(pipelineRun, random, 'IDM K8s Postcommit', clusterConfig +
                            [TESTS_SCOPE: 'tests/k8s/postcommit/idm',]
                    )
                }
        )
    }
    if (params.Postcommit_idm_k8s_upgrade) {
        parallelTestsMap.put('IDM K8s Upgrade',
                {
                    def randomNumber = random.nextInt(99999) + 100000 // 6 digit random number to compute to namespace
                    def upgradeCommonConfig = clusterConfig + [
                            TESTS_SCOPE         : 'tests/k8s/postcommit/idm',
                            DEPLOYMENT_NAMESPACE: cloud_config.commonConfig()['DEPLOYMENT_NAMESPACE'] + '-' +
                                    randomNumber,
                    ]

                    def deploymentConfig = upgradeCommonConfig + [
                            REPORT_NAME_PREFIX       : 'idm_k8s_upgrade_deployment',
                            SKIP_TESTS               : true,
                            SKIP_CLEANUP             : true,
                            STASH_PLATFORM_IMAGES_REF: commonLodestarModule.fraasProductionTag,
                            STASH_FORGEOPS_REF       : commonLodestarModule.forgeopsFraasProduction,
                    ]

                    def idmLatestPromotedTag = commonLodestarModule.getPromotedProductTag(commonModule.DEFAULT_PLATFORM_IMAGES_TAG, 'idm')
                    def idmLatestPromotedRepo = commonLodestarModule.getPromotedProductRepo(commonModule.DEFAULT_PLATFORM_IMAGES_TAG, 'idm')

                    def testConfig = upgradeCommonConfig + [
                            REPORT_NAME_PREFIX                     : 'idm_k8s_upgrade_upgrade',
                            SKIP_DEPLOY                            : true,
                            DEPLOYMENT_UPGRADE_FIRST               : true,
                            COMPONENTS_IDM_IMAGE_UPGRADE_TAG       : idmLatestPromotedTag,
                            COMPONENTS_IDM_IMAGE_UPGRADE_REPOSITORY: idmLatestPromotedRepo,
                    ]

                    commonLodestarModule.runUpgrade(pipelineRun, random, 'IDM K8s Upgrade', deploymentConfig, testConfig)
                }
        )
    }

    if (params.Postcommit_ig_k8s_postcommit) {
        parallelTestsMap.put('IG K8s Postcommit',
                {
                    commonLodestarModule.runSpyglaas(pipelineRun, random, 'IG K8s Postcommit', clusterConfig +
                            [TESTS_SCOPE: 'tests/k8s/postcommit/ig']
                    )
                }
        )
    }
    if (params.Postcommit_ig_k8s_upgrade) {
        parallelTestsMap.put('IG K8s Upgrade',
                {
                    def randomNumber = random.nextInt(99999) + 100000 // 6 digit random number to compute to namespace
                    def upgradeCommonConfig = clusterConfig + [
                            TESTS_SCOPE         : 'tests/k8s/postcommit/ig',
                            DEPLOYMENT_NAMESPACE: cloud_config.commonConfig()['DEPLOYMENT_NAMESPACE'] + '-' +
                                    randomNumber,
                    ]

                    def deploymentConfig = upgradeCommonConfig + [
                            REPORT_NAME_PREFIX       : 'ig_k8s_upgrade_deployment',
                            SKIP_TESTS               : true,
                            SKIP_CLEANUP             : true,
                            STASH_PLATFORM_IMAGES_REF: commonLodestarModule.fraasProductionTag,
                            STASH_FORGEOPS_REF       : commonLodestarModule.forgeopsFraasProduction,
                    ]

                    def igLatestPromotedTag = commonLodestarModule.getPromotedProductTag(commonModule.DEFAULT_PLATFORM_IMAGES_TAG, 'ig')
                    def igLatestPromotedRepo = commonLodestarModule.getPromotedProductRepo(commonModule.DEFAULT_PLATFORM_IMAGES_TAG, 'ig')

                    def testConfig = upgradeCommonConfig + [
                            REPORT_NAME_PREFIX                    : 'ig_k8s_upgrade_upgrade',
                            SKIP_DEPLOY                           : true,
                            DEPLOYMENT_UPGRADE_FIRST              : true,
                            COMPONENTS_IG_IMAGE_UPGRADE_TAG       : igLatestPromotedTag,
                            COMPONENTS_IG_IMAGE_UPGRADE_REPOSITORY: igLatestPromotedRepo,
                    ]

                    commonLodestarModule.runUpgrade(pipelineRun, random, 'IG K8s Upgrade', deploymentConfig, testConfig)
                }
        )
    }

    if (params.Postcommit_platform_ui) {
        parallelTestsMap.put('Platform UI',
                {
                    commonLodestarModule.runPlatformUi(pipelineRun, random, 'Platform UI', clusterConfig)
                }
        )
    }

    if (params.Postcommit_set_images) {
        parallelTestsMap.put('Set Images',
                {
                    commonLodestarModule.runSpyglaas(pipelineRun, random, 'Set Images', clusterConfig +
                            [TESTS_SCOPE              : 'tests/set_images',
                             STASH_PLATFORM_IMAGES_REF: commonLodestarModule.fraasProductionTag,
                             STASH_FORGEOPS_REF       : commonLodestarModule.forgeopsFraasProduction,
                             // Need a specific commit in which we have a fix for the set-images tests
                             // TODO: to remove once fraas-production tag is moved to idcloud-2022.10
                             STASH_LODESTAR_REF       : '91438c58e803b47c44dbee90cb7a6afad664e5a6']
                    )
                }
        )
    }

    parallel parallelTestsMap
}

return this
