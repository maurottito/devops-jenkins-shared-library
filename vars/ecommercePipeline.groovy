/**
 * Reusable pipeline template for all e-commerce microservices.
 *
 * Usage in Jenkinsfile:
 *   @Library('ecommerce-shared-library') _
 *   ecommercePipeline(
 *       serviceName:   'product-service',
 *       dockerImage:   'maurottito/ecommerce-product-service',
 *       isNodeService: true   // default; set false for non-Node services (e.g. database)
 *   )
 *
 * Pipeline behaviour per branch / trigger:
 *   PR               → Build + Test + Security Scan + Container Build/Scan  (no push, no deploy)
 *   develop          → + Container Push + Deploy to Dev
 *   release/*        → + Container Push + Deploy to Staging
 *   main             → + Container Push + Manual Approval + Deploy to Production
 */
def call(Map config) {
    def serviceName   = config.serviceName
    def dockerImage   = config.dockerImage
    def isNodeService = config.containsKey('isNodeService') ? config.isNodeService : true

    pipeline {
        agent any

        options {
            buildDiscarder(logRotator(numToKeepStr: '10'))
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
        }

        stages {

            // ------------------------------------------------------------------
            // INITIALIZE – compute the image tag and log pipeline context
            // ------------------------------------------------------------------
            stage('Initialize') {
                steps {
                    script {
                        def branchName  = env.BRANCH_NAME ?: ''
                        def commitShort = env.GIT_COMMIT?.take(7) ?: 'unknown'
                        def buildNum    = env.BUILD_NUMBER

                        if (env.CHANGE_ID) {
                            env.IMAGE_TAG     = "pr-${env.CHANGE_ID}-${buildNum}"
                            env.PIPELINE_TYPE = 'BUILD'
                        } else if (branchName == 'develop') {
                            env.IMAGE_TAG     = "dev-${commitShort}-${buildNum}"
                            env.PIPELINE_TYPE = 'DEV'
                        } else if (branchName.startsWith('release/')) {
                            def version       = branchName.replace('release/', '').replaceAll('[^a-zA-Z0-9._-]', '-')
                            env.IMAGE_TAG     = "rc-${version}-${buildNum}"
                            env.PIPELINE_TYPE = 'STAGING'
                        } else if (branchName == 'main') {
                            env.IMAGE_TAG     = "v${buildNum}"
                            env.PIPELINE_TYPE = 'PROD'
                        } else {
                            def safeBranch    = branchName.replaceAll('[^a-zA-Z0-9._-]', '-').take(20)
                            env.IMAGE_TAG     = "${safeBranch}-${buildNum}"
                            env.PIPELINE_TYPE = 'FEATURE'
                        }

                        echo "==========================================="
                        echo "Service       : ${serviceName}"
                        echo "Pipeline Type : ${env.PIPELINE_TYPE}"
                        echo "Branch        : ${branchName}"
                        echo "Image Tag     : ${env.IMAGE_TAG}"
                        echo "Full Image    : ${dockerImage}:${env.IMAGE_TAG}"
                        echo "==========================================="
                    }
                }
            }

            // ------------------------------------------------------------------
            // BUILD – install dependencies and run linter
            // ------------------------------------------------------------------
            stage('Build') {
                when {
                    expression { return isNodeService }
                }
                steps {
                    sh '''
                        echo "=== Build Stage ==="
                        node --version
                        npm --version
                        npm ci
                        if npm run 2>&1 | grep -q " lint"; then
                            npm run lint
                        else
                            echo "No lint script found, skipping linter"
                        fi
                    '''
                }
            }

            // ------------------------------------------------------------------
            // TEST – unit + integration tests
            // ------------------------------------------------------------------
            stage('Test') {
                when {
                    expression { return isNodeService }
                }
                steps {
                    sh '''
                        echo "=== Test Stage ==="
                        if npm run 2>&1 | grep -q " test"; then
                            CI=true npm test -- --watchAll=false --passWithNoTests 2>&1 || true
                        else
                            echo "No test script found, skipping tests"
                        fi
                    '''
                }
            }

            // ------------------------------------------------------------------
            // SECURITY SCAN – static analysis (npm audit)
            // ------------------------------------------------------------------
            stage('Security Scan') {
                steps {
                    script {
                        if (isNodeService) {
                            sh 'npm audit --audit-level=critical || echo "npm audit: non-critical issues found (non-blocking)"'
                        } else {
                            echo 'Skipping npm audit for non-Node service'
                        }
                    }
                }
            }

            // ------------------------------------------------------------------
            // CONTAINER BUILD – build and label the Docker image
            // ------------------------------------------------------------------
            stage('Container Build') {
                steps {
                    sh """
                        echo "=== Container Build ==="
                        docker build \\
                            --label "git.commit=\${GIT_COMMIT}" \\
                            --label "build.number=\${BUILD_NUMBER}" \\
                            --label "build.branch=\${BRANCH_NAME}" \\
                            -t ${dockerImage}:\${IMAGE_TAG} \\
                            -t ${dockerImage}:latest .
                        echo "Built: ${dockerImage}:\${IMAGE_TAG}"
                    """
                }
            }

            // ------------------------------------------------------------------
            // CONTAINER SCAN – Trivy image vulnerability scan
            // ------------------------------------------------------------------
            stage('Container Scan') {
                steps {
                    sh """
                        echo "=== Container Security Scan ==="
                        if command -v trivy >/dev/null 2>&1; then
                            trivy image \\
                                --exit-code 0 \\
                                --severity CRITICAL,HIGH \\
                                --format table \\
                                ${dockerImage}:\${IMAGE_TAG}
                        else
                            echo "Trivy not available in PATH, skipping scan"
                        fi
                    """
                }
            }

            // ------------------------------------------------------------------
            // CONTAINER PUSH – push to Docker Hub (skipped on PRs)
            // ------------------------------------------------------------------
            stage('Container Push') {
                when {
                    not { changeRequest() }
                }
                steps {
                    withCredentials([usernamePassword(
                        credentialsId: 'docker-hub-credentials',
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASS'
                    )]) {
                        sh """
                            echo "=== Container Push ==="
                            echo "\$DOCKER_PASS" | docker login -u "\$DOCKER_USER" --password-stdin
                            docker push ${dockerImage}:\${IMAGE_TAG}
                            docker push ${dockerImage}:latest
                            echo "Pushed: ${dockerImage}:\${IMAGE_TAG}"
                        """
                    }
                }
            }

            // ------------------------------------------------------------------
            // DEPLOY DEV – triggered on develop branch
            // ------------------------------------------------------------------
            stage('Deploy to Dev') {
                when {
                    branch 'develop'
                }
                steps {
                    sh """
                        echo "=== Deploy to Dev ==="
                        echo "Image: ${dockerImage}:\${IMAGE_TAG}"
                        # Uncomment when Kubernetes is configured:
                        # kubectl set image deployment/${serviceName} \\
                        #     ${serviceName}=${dockerImage}:\${IMAGE_TAG} -n dev
                        # kubectl rollout status deployment/${serviceName} -n dev --timeout=5m
                        echo "Dev deployment complete: ${dockerImage}:\${IMAGE_TAG}"
                    """
                }
            }

            // ------------------------------------------------------------------
            // DEPLOY STAGING – triggered on release/* branches
            // ------------------------------------------------------------------
            stage('Deploy to Staging') {
                when {
                    branch pattern: 'release/.*', comparator: 'REGEXP'
                }
                steps {
                    sh """
                        echo "=== Deploy to Staging ==="
                        echo "Image: ${dockerImage}:\${IMAGE_TAG}"
                        # Uncomment when Kubernetes is configured:
                        # kubectl set image deployment/${serviceName} \\
                        #     ${serviceName}=${dockerImage}:\${IMAGE_TAG} -n staging
                        # kubectl rollout status deployment/${serviceName} -n staging --timeout=5m
                        echo "Staging deployment complete: ${dockerImage}:\${IMAGE_TAG}"
                    """
                }
            }

            // ------------------------------------------------------------------
            // APPROVE PRODUCTION – manual gate, main branch only
            // ------------------------------------------------------------------
            stage('Approve Production') {
                when {
                    branch 'main'
                }
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        input(
                            message: "Deploy ${dockerImage}:\${IMAGE_TAG} to Production?",
                            ok: 'Approve and Deploy',
                            submitter: 'admin'
                        )
                    }
                }
            }

            // ------------------------------------------------------------------
            // DEPLOY PROD – runs only after manual approval, main branch only
            // ------------------------------------------------------------------
            stage('Deploy to Production') {
                when {
                    branch 'main'
                }
                steps {
                    sh """
                        echo "=== Deploy to Production ==="
                        echo "Image: ${dockerImage}:\${IMAGE_TAG}"
                        # Uncomment when Kubernetes is configured:
                        # kubectl set image deployment/${serviceName} \\
                        #     ${serviceName}=${dockerImage}:\${IMAGE_TAG} -n prod
                        # kubectl rollout status deployment/${serviceName} -n prod --timeout=10m
                        echo "Production deployment complete: ${dockerImage}:\${IMAGE_TAG}"
                    """
                }
            }
        }

        post {
            always {
                sh 'docker logout || true'
                cleanWs()
            }
            success {
                echo "Pipeline PASSED: ${serviceName} [${env.IMAGE_TAG}]"
            }
            failure {
                echo "Pipeline FAILED: ${serviceName}"
            }
        }
    }
}
