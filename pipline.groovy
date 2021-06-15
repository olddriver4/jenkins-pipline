
pipeline {
    agent any
    parameters {
        // 提供要部署的服务器选项
        string(
            name: 'ROLLBACK_VERSION',
            defaultValue: '',
            description: "------------------The default parameter does not perform rollback-----------------\nRollback needs to perform a numerical value, PS >Version: 1.0.1-devnet"
        )
        extendedChoice( 
            name: 'SERVICE_NAME', 
            description: 'Please select which module to build ?', 
            multiSelectDelimiter: ',',
            visibleItemCount: 10,
            quoteValue: false, 
            saveJSONParameterToFile: false, 
            type: 'PT_RADIO',
            defaultValue: 'all',
            value: 'all, infura-sync, infura-node-management, infura-rpc',
        )
        extendedChoice( 
            name: 'BRANCH_NAME', 
            description: 'Please select which branch to build ?', 
            multiSelectDelimiter: ',',
            visibleItemCount: 10,
            quoteValue: false, 
            saveJSONParameterToFile: false, 
            type: 'PT_SINGLE_SELECT', 
            defaultValue: 'devnet',
            value: 'devnet, testnet, mainnet',
        )
        /*
        booleanParam(name: 'isAll', defaultValue: false, description: '注释')     
        string(name: 'update', defaultValue: '', description: '注释')    
         */
    }

    tools {
        go 'go 1.16.4'
    }

    
    triggers{
        GenericTrigger(
            genericVariables: [
            [key: 'ref', value: '$.ref']
            ],
            causeString: 'Triggered on $ref',
            token: "2UldPcs1iUWTmSs6nOIOw", //变更
            printContributedVariables: true,
            printPostContent: true,
            silentResponse: false,
            regexpFilterText: '$ref',
            regexpFilterExpression: '^refs/heads/(devnet)$' //变更
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))// 表示保留10次构建历史
        disableConcurrentBuilds() //不允许同时构建
        timeout(time: 60, unit: 'MINUTES') //整个pipline超时
        timestamps() //输出构建时间日志
        //checkoutToSubdirectory('testdir') //checkout目录
    }

    environment {
        PROJECT_NAME = 'infura' //变更

        SSH_SUFFIX_NGX_BJ = 'db-BJ' //Nginx BJ 服务存放 变更
        SSH_SUFFIX_NGX_HK = 'db-HK' //Nginx HK 服务存放 变更
        SSH_SUFFIX_BACK_BJ = 'backend-BJ' //Backend BJ 服务存放 变更
        SSH_SUFFIX_BACK_HK = 'backend-HK' //Nginx HK 服务存放 变更
        SSH_DEV_BACK = 'infura-devnet-backend-HK' //Dev 服务器固定

        UPSTREAM_TAG_BACKEND = 'infura-backend' //变更 BJ and HK
        UPSTREAM_TAG_RPC = 'infura-fullnode' //变更 BJ and HK

        DET_DIR = '/data/docker-compose/bin' //变更

        BACKEND_BIN = '/data/tool/release.sh'
        FRONTEND_BIN = '/data/tool/switch_upstream.sh'

        GIT_URL = 'git@github.com:Conflux-Dev/conflux-infura.git' //变更
        GIT_TAG = "git ls-remote --exit-code --refs --tags ${env.GIT_URL} |grep ${env.BRANCH_NAME} |tail -n1 |awk -F '/' '{print \$3}'" //根据需求变更

        DING_TOKEN = '' //变更
    }
    
    stages {
        stage("build description") {
            environment {
                GIT_TAG = sh ( returnStdout: true, script: "${env.GIT_TAG}")
            }
            steps {
                script {
                    currentBuild.displayName = "Deploy on ${params.BRANCH_NAME} ${env.GIT_TAG} [#${BUILD_NUMBER}]"
                    currentBuild.description = "Project: ${env.PROJECT_NAME}\n" +
                                               "Branch: ${params.BRANCH_NAME}\n"
                }
            }
        }

        stage ('build ago') {
            when {
                allOf {
                    expression {
                        params.ROLLBACK_VERSION == '' // && params.ROLLBACK_VERSION != 'devnet'
                    }
                }
            }
            parallel {
                stage('checkout') {
                    steps {
                        //deleteDir() /* clean up our workspace */
                        echo "Start checkout github ing ..."
                        git branch: "${params.BRANCH_NAME}", credentialsId: 'jenkins', url: "${env.GIT_URL}"
                    }
                }

                stage('upload package') {
                    //agent { label "jenkins-slave" }
                    steps {
                        script {
                            if ( "${params.BRANCH_NAME}" == "devnet" ) {
                                try {
                                    echo "${params.BRANCH_NAME}: Start to compile the package ..."
                                    sh """
                                        go mod download
                                        go build -o ${env.PROJECT_NAME}
                                        
                                        echo "${params.BRANCH_NAME} Start upload package ..."

                                        scp ${env.PROJECT_NAME} ${env.SSH_DEV_BACK}:${env.DET_DIR}
                                        rm -f ${env.PROJECT_NAME}
                                    """
                                }
                                catch (err) {
                                    err "${params.BRANCH_NAME}: Failed to compile or upload the package !"
                                }
                            }else {
                                try {
                                    echo "${params.BRANCH_NAME} Start to compile the package ..."
                                    sh """
                                        go mod download
                                        go build -o ${env.PROJECT_NAME}
                                        
                                        echo "${params.BRANCH_NAME} Start upload package ..."

                                        scp ${env.PROJECT_NAME} ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_BACK_BJ}:${env.DET_DIR}
                                        scp ${env.PROJECT_NAME} ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_BACK_HK}:${env.DET_DIR}
                                        rm -f ${env.PROJECT_NAME}
                                    """
                                }
                                catch (err) {
                                    err "${params.BRANCH_NAME}: Failed to compile or upload the package !"
                                }
                            }
                        }
                    }
                }
            }
        }

        stage('release devnet') {
            when {
                allOf {
                    expression {
                        params.BRANCH_NAME == 'devnet' && params.ROLLBACK_VERSION == ''
                    }
                }
            }
            environment {
                GIT_TAG = sh ( returnStdout: true, script: "${env.GIT_TAG}")
            }
            steps {
                script {
                    try {
                        echo "Starting release devnet..."
                        sh """
                            ssh ${env.SSH_DEV_BACK} ${env.BACKEND_BIN}  ${env.PROJECT_NAME} ${params.SERVICE_NAME} ${params.BRANCH_NAME} stop
                            ssh ${env.SSH_DEV_BACK} ${env.BACKEND_BIN}  ${env.PROJECT_NAME} ${params.SERVICE_NAME} ${params.BRANCH_NAME} start ${env.GIT_TAG}
                        """
                    }
                    catch (err) {
                        err "Failed to release devnet !"
                    }
                }
            }
        }

        stage('release BJ') {
            when {
                allOf {
                    expression {
                        params.BRANCH_NAME != 'devnet' && params.ROLLBACK_VERSION == ''
                    }
                }
            }
            environment {
                GIT_TAG = sh ( returnStdout: true, script: "${env.GIT_TAG}")
                FRONTEND_UPSTREAM = "/usr/local/openresty/nginx/upstream/${env.PROJECT_NAME}-${params.BRANCH_NAME}.conf" //变更
            }
            steps {
                script {
                    try {
                        echo "traffic switching BJ..."
                        sh """
                            ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_BJ} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_RPC} restore
                            ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_BJ} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_BACKEND} update
                        """

                        echo "Starting release BJ..."
                        sh """
                            ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_BACK_BJ} ${env.BACKEND_BIN}  ${env.PROJECT_NAME} ${params.SERVICE_NAME} ${params.BRANCH_NAME} stop
                            ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_BACK_BJ} ${env.BACKEND_BIN}  ${env.PROJECT_NAME} ${params.SERVICE_NAME} ${params.BRANCH_NAME} start ${env.GIT_TAG}
                        """

                        echo "Starting traffic switch restore BJ..."
                        sh """
                            ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_BJ} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_BACKEND} restore
                            ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_BJ} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_RPC} update
                        """
                    }
                    catch (err) {
                        err "Failed to release BJ !"
                    }
                }
            }
        }


        stage('release HK') {
            when {
                allOf {
                    expression {
                        params.BRANCH_NAME != 'devnet' && params.ROLLBACK_VERSION == ''
                    }
                }
            }
            environment {
                GIT_TAG = sh ( returnStdout: true, script: "${env.GIT_TAG}")
                FRONTEND_UPSTREAM = "/usr/local/openresty/nginx/upstream/${env.PROJECT_NAME}-${params.BRANCH_NAME}.conf" //变更
            }

            steps {
                script {
                    try {
                        echo "Starting traffic switching HK..."
                        sh """
                            ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_HK} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_RPC} restore
                            ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_HK} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_BACKEND} update
                        """

                        echo "Starting release HK..."
                        sh """
                            ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_BACK_HK} ${env.BACKEND_BIN}  ${env.PROJECT_NAME} ${params.SERVICE_NAME} ${params.BRANCH_NAME} stop
                            ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_BACK_HK} ${env.BACKEND_BIN}  ${env.PROJECT_NAME} ${params.SERVICE_NAME} ${params.BRANCH_NAME} start ${env.GIT_TAG}
                        """

                        echo "Starting traffic switch restore HK..."
                        sh """
                            ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_HK} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_BACKEND} restore
                            ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_HK} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_RPC} update
                        """
                    }
                    catch (err) {
                        err "Failed to release HK !"
                    }
                }
            }
        }

        stage('rollback') {
            when {
                allOf {
                    expression {
                        env.BRANCH_NAME != 'devnet' && env.ROLLBACK_VERSION != ''
                    }
                }
            }
            environment {
                FRONTEND_UPSTREAM = "/usr/local/openresty/nginx/upstream/${env.PROJECT_NAME}-${params.BRANCH_NAME}.conf" //变更
            }
            stages {    
                stage('rollback BJ') {
                    input {
                        message "Should we continue?"
                        ok "Yes, we should."
                        parameters {
                            choice(
                                description: 'Who should I say hello to?',
                                name: 'whether',
                                choices: ['yes', 'no']
                            )
                        }
                    }
                    steps {
                        script {
                            if ( "${env.whether}" == "yes" ) {
                                try {
                                    echo "Rollback: starting traffic switching BJ..."
                                    sh """
                                        ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_BJ} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_RPC} restore
                                        ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_BJ} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_BACKEND} update
                                    """

                                    echo "Rollback: Starting release BJ..."
                                    sh "ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_BACK_BJ} ${env.BACKEND_BIN}  ${env.PROJECT_NAME} ${params.SERVICE_NAME} ${params.BRANCH_NAME} rollback ${params.ROLLBACK_VERSION}"

                                    echo "Rollback: traffic switch restore BJ..."
                                    sh """
                                        ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_BJ} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_BACKEND} restore
                                        ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_BJ} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_RPC} update
                                    """
                                }
                                catch (err) {
                                    err "Failed to release BJ !"
                                }
                            }else {
                                echo "rollback BJ: Do not switch traffic ! exit ."
                                sh 'exit 1'
                            }
                        }
                    }
                }
                stage('rollback HK') {
                    input {
                        message "Should we continue?"
                        ok "Yes, we should."
                        parameters {
                            choice(
                                description: 'Who should I say hello to?',
                                name: 'whether',
                                choices: ['yes', 'no']
                            )
                        }
                    }
                    steps {
                        script {
                            if ( "${env.whether}" == "yes" ) {
                                try {
                                    echo "Rollback: starting traffic switching HK..."
                                    sh """
                                        ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_HK} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_RPC} restore
                                        ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_HK} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_BACKEND} update
                                    """

                                    echo "Rollback: Starting release HK..."
                                    sh "ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_BACK_HK} ${env.BACKEND_BIN}  ${env.PROJECT_NAME} ${params.SERVICE_NAME} ${params.BRANCH_NAME} rollback ${params.ROLLBACK_VERSION}"

                                    echo "Rollback: traffic switch restore HK..."
                                    sh """
                                        ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_HK} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_BACKEND} restore
                                        ssh ${env.PROJECT_NAME}-${params.BRANCH_NAME}-${env.SSH_SUFFIX_NGX_HK} ${env.FRONTEND_BIN} ${env.FRONTEND_UPSTREAM} ${env.UPSTREAM_TAG_RPC} update
                                    """
                                }
                                catch (err) {
                                    err "Failed to release HK !"
                                }
                            }else {
                                echo "rollback HK: Do not switch traffic ! exit ."
                                sh 'exit 1'
                            }
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            wrap([$class: 'BuildUser']) {
                script {
                    //GIT_INFO
                    GIT_TAG = sh ( returnStdout: true, script: "git ls-remote --exit-code --refs --tags ${env.GIT_URL} |tail -n1 |awk -F '/' '{print \$3}'")
                    GIT_COMMT_ID = sh ( returnStdout: true, script: "git rev-parse --short HEAD")
                    GIT_COMMIT_USER = sh ( returnStdout: true, script: "git --no-pager show -s --format='%an'")
                    GIT_COMMIT_LOG = sh ( returnStdout: true, script: "git --no-pager show -s --format='%s'")

                    //BUILD_USER_ID = "${env.BUILD_USER_ID}"
                    BUILD_USER = "${env.BUILD_USER}"
                    //BUILD_USER_EMAIL = "${env.BUILD_USER_EMAIL}"
                }
            }
            dingtalk (
                robot: "${env.DING_TOKEN}", //dingding token
                type:'MARKDOWN',
                atAll: false,
                title: "Success: ${JOB_NAME}/${params.SERVICE_NAME}/${ROLLBACK_VERSION}",
                //messageUrl: 'xxxx',
                text: [
                    //"<font color="#000066" size="5" >Aborted  [${JOB_NAME}](${BUILD_URL}) </font><br />",
                    "### [${JOB_NAME}/${params.SERVICE_NAME}/${ROLLBACK_VERSION}](${BUILD_URL})",
                    ">- build event：**Build Success ✅**",
                    ">- build time: ${currentBuild.durationString}",
                    ">- branch: ${params.BRANCH_NAME}",
                    ">- tag: ${GIT_TAG}",
                    ">- commit id: ${GIT_COMMT_ID}",
                    ">- commit user: ${GIT_COMMIT_USER}",
                    ">- chage log: ${GIT_COMMIT_LOG}",
                    ">- build user: ${BUILD_USER}",
                ]
            )
        }

        failure {
            wrap([$class: 'BuildUser']) {
                script {
                    //GIT_INFO
                    GIT_TAG = sh ( returnStdout: true, script: "git ls-remote --exit-code --refs --tags ${env.GIT_URL} |tail -n1 |awk -F '/' '{print \$3}'")
                    GIT_COMMT_ID = sh ( returnStdout: true, script: "git rev-parse --short HEAD")
                    GIT_COMMIT_USER = sh ( returnStdout: true, script: "git --no-pager show -s --format='%an'")
                    GIT_COMMIT_LOG = sh ( returnStdout: true, script: "git --no-pager show -s --format='%s'")

                    //BUILD_USER_ID = "${env.BUILD_USER_ID}"
                    BUILD_USER = "${env.BUILD_USER}"
                    //BUILD_USER_EMAIL = "${env.BUILD_USER_EMAIL}"
                }
            }
            dingtalk (
                robot: "${env.DING_TOKEN}", //dingding token
                type:'MARKDOWN',
                atAll: false,
                title: "Failure: ${JOB_NAME}/${params.SERVICE_NAME}/${ROLLBACK_VERSION}",
                //messageUrl: 'xxxx',
                text: [
                    "### [${JOB_NAME}/${params.SERVICE_NAME}/${ROLLBACK_VERSION}](${BUILD_URL})",
                    ">- build event：**Build Fail❌**",
                    ">- build time: ${currentBuild.durationString}",
                    ">- branch: ${params.BRANCH_NAME}",
                    ">- tag: ${GIT_TAG}",
                    ">- commit id: ${GIT_COMMT_ID}",
                    ">- commit user: ${GIT_COMMIT_USER}",
                    ">- chage log: ${GIT_COMMIT_LOG}",
                    ">- build user: ${BUILD_USER}",
                ]
            )
        }

        unstable {
            wrap([$class: 'BuildUser']) {
                script {
                    //GIT_INFO
                    GIT_TAG = sh ( returnStdout: true, script: "git ls-remote --exit-code --refs --tags ${env.GIT_URL} |tail -n1 |awk -F '/' '{print \$3}'")
                    GIT_COMMT_ID = sh ( returnStdout: true, script: "git rev-parse --short HEAD")
                    GIT_COMMIT_USER = sh ( returnStdout: true, script: "git --no-pager show -s --format='%an'")
                    GIT_COMMIT_LOG = sh ( returnStdout: true, script: "git --no-pager show -s --format='%s'")

                    //BUILD_USER_ID = "${env.BUILD_USER_ID}"
                    BUILD_USER = "${env.BUILD_USER}"
                    //BUILD_USER_EMAIL = "${env.BUILD_USER_EMAIL}"
                }
            }
            dingtalk (
                robot: "${env.DING_TOKEN}", //dingding token
                type:'MARKDOWN',
                atAll: false,
                title: "Unstable: ${JOB_NAME}/${params.SERVICE_NAME}/${ROLLBACK_VERSION}",
                //messageUrl: 'xxxx',
                text: [
                    "### [${JOB_NAME}/${params.SERVICE_NAME}/${ROLLBACK_VERSION}](${BUILD_URL})",
                    ">- build event：**Build Unstable❓**",
                    ">- build time: ${currentBuild.durationString}",
                    ">- branch: ${params.BRANCH_NAME}",
                    ">- tag: ${GIT_TAG}",
                    ">- commit id: ${GIT_COMMT_ID}",
                    ">- commit user: ${GIT_COMMIT_USER}",
                    ">- chage log: ${GIT_COMMIT_LOG}",
                    ">- build user: ${BUILD_USER}",
                ]
            )
        }

        aborted {
            wrap([$class: 'BuildUser']) {
                script {
                    //GIT_INFO
                    GIT_TAG = sh ( returnStdout: true, script: "git ls-remote --exit-code --refs --tags ${env.GIT_URL} |tail -n1 |awk -F '/' '{print \$3}'")
                    GIT_COMMT_ID = sh ( returnStdout: true, script: "git rev-parse --short HEAD")
                    GIT_COMMIT_USER = sh ( returnStdout: true, script: "git --no-pager show -s --format='%an'")
                    GIT_COMMIT_LOG = sh ( returnStdout: true, script: "git --no-pager show -s --format='%s'")

                    //BUILD_USER_ID = "${env.BUILD_USER_ID}"
                    BUILD_USER = "${env.BUILD_USER}"
                    //BUILD_USER_EMAIL = "${env.BUILD_USER_EMAIL}"
                }
            }
            dingtalk (
                robot: "${env.DING_TOKEN}", //dingding token
                type:'MARKDOWN',
                atAll: false,
                title: "Aborted: ${JOB_NAME}/${params.SERVICE_NAME}/${ROLLBACK_VERSION}",
                //messageUrl: 'xxxx',
                text: [
                    "### [${JOB_NAME}/${params.SERVICE_NAME}/${ROLLBACK_VERSION}](${BUILD_URL})",
                    ">- build event：**Build Aborted❗**",
                    ">- build time: ${currentBuild.durationString}",
                    ">- branch: ${params.BRANCH_NAME}",
                    ">- tag: ${GIT_TAG}",
                    ">- commit id: ${GIT_COMMT_ID}",
                    ">- commit user: ${GIT_COMMIT_USER}",
                    ">- chage log: ${GIT_COMMIT_LOG}",
                    ">- build user: ${BUILD_USER}",
                ]
            )
        }
    }
}

