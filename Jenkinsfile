// ============================================================
// Jenkinsfile — 单 JAR 合体声明式流水线
// ------------------------------------------------------------
// 【阶段编排】Frontend → Test → Package → Image → Publish
//   Frontend : npm install + vite build（产物 → build/frontend-static/）
//   Test     : 142 项单测（JUnit 报告归档）
//   Package  : bootJar 单 JAR 合体打包（前端产物 + python 脚本自动入 jar）
//   Image    : docker build 本地轨（COPY 上一步 jar，复用流水线内构建产物，
//              不走 Dockerfile.full 容器内重复构建——全构建轨留给"仅有
//              Docker"的复现场景）
//   Publish  : docker push（REGISTRY 未配置时跳过）
//
// 【镜像 tag】text-vector-engine:${BUILD_NUMBER}，成功后追加 latest 指针
//
// 【节点要求】docker + JDK8 + Node；gradlew 经 wrapper 钉住 Gradle 7.6.4，
//   节点无需预装 Gradle
// ============================================================

pipeline {
    agent any

    options {
        // 老构建不自动清除，保留 10 次便于回溯
        buildDiscarder(logRotator(numToKeepStr: '10'))
        // 单节点串行：data/、8090 端口等资源不可并发占用
        disableConcurrentBuilds()
    }

    environment {
        // 镜像名与推送目标：REGISTRY 为空则 Publish 阶段跳过
        //   例：REGISTRY = 'registry.example.com/team'
        IMAGE_NAME      = 'text-vector-engine'
        REGISTRY        = ''
        // 国内加速（与本地开发一致的网络策略；节点在墙外可置空走官方源）
        NPM_REGISTRY    = 'https://registry.npmmirror.com'
        // Windows 仓库 CRLF 防御开关（gradlew 被 checkout 为 CRLF 时需去 CR）
        STRIP_CR        = 'true'
    }

    stages {

        // ----------------------------------------------------
        // 1. Frontend — npm install + vite build
        //    （buildFrontend 内含 fingerprint 缓存：package-lock 未变则秒过）
        // ----------------------------------------------------
        stage('Frontend') {
            steps {
                sh '''
                    if [ "$STRIP_CR" = "true" ]; then sed -i 's/\\r$//' gradlew; fi
                    chmod +x gradlew
                    ./gradlew :engine-server:buildFrontend --no-daemon
                '''
            }
        }

        // ----------------------------------------------------
        // 2. Test — 全量单测（JUnit 报告归档，失败即断流水线）
        // ----------------------------------------------------
        stage('Test') {
            steps {
                sh './gradlew :engine-server:test --no-daemon'
            }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: 'engine-server/build/test-results/test/*.xml'
                }
            }
        }

        // ----------------------------------------------------
        // 3. Package — 单 JAR 合体 fat jar
        //    （processResources 自动拉起 copyFrontendDist + packagePython：
        //      前端产物 → BOOT-INF/classes/static/，
        //      python 脚本 → BOOT-INF/classes/python/）
        // ----------------------------------------------------
        stage('Package') {
            steps {
                sh './gradlew :engine-server:bootJar --no-daemon'
            }
            post {
                success {
                    archiveArtifacts artifacts: 'engine-server/build/libs/engine-server.jar',
                                     fingerprint: true
                }
            }
        }

        // ----------------------------------------------------
        // 4. Image — docker build（本地轨：COPY 上一步构建的 jar）
        //    构建上下文 = 仓库根；pip/pypi/HF 加速参数与本地开发一致
        // ----------------------------------------------------
        stage('Image') {
            steps {
                sh '''
                    docker build -f docker/Dockerfile \
                        -t "${IMAGE_NAME}:${BUILD_NUMBER}" \
                        -t "${IMAGE_NAME}:latest" \
                        .
                '''
            }
        }

        // ----------------------------------------------------
        // 5. Publish — 推送镜像（REGISTRY 未配置时跳过）
        // ----------------------------------------------------
        stage('Publish') {
            when {
                expression { return env.REGISTRY?.trim() }
            }
            steps {
                sh '''
                    docker tag "${IMAGE_NAME}:${BUILD_NUMBER}" "${REGISTRY}/${IMAGE_NAME}:${BUILD_NUMBER}"
                    docker tag "${IMAGE_NAME}:latest"         "${REGISTRY}/${IMAGE_NAME}:latest"
                    docker push "${REGISTRY}/${IMAGE_NAME}:${BUILD_NUMBER}"
                    docker push "${REGISTRY}/${IMAGE_NAME}:latest"
                '''
            }
        }
    }

    post {
        failure {
            echo '流水线失败：见上方各阶段日志（Frontend 构建日志含 vite 输出，Test 报告见 JUnit 归档）'
        }
        always {
            cleanWs notFailBuild: true   // 干净工作区，下次构建从零复现
        }
    }
}
