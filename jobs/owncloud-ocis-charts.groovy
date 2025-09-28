pipeline {
    agent {
        kubernetes {
            yaml """
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins
  containers:
  - name: helm
    image: alpine/helm:3.14.0
    command:
    - cat
    tty: true
  - name: git
    image: alpine/git:2.40.1
    command:
    - cat
    tty: true
"""
        }
    }
    
    environment {
        REGISTRY_URL = 'oci://harbor.anymus.pro/anymus-helm-pub'
        CHART_NAME = 'ocis'
        CHART_VERSION = "${BUILD_NUMBER}"
    }
    
    stages {
        stage('Clone Helm Chart Repository') {
            steps {
                container('git') {
                    // Clone the Helm chart repository
                    git branch: 'main', url: 'https://github.com/owncloud/ocis-charts.git'
                    dir('charts/ocis') {
                        script {
                            if (fileExists('Chart.yaml')) {
                                def chartYaml = readYaml file: 'Chart.yaml'
                                env.CHART_VERSION = chartYaml.version ?: env.BUILD_NUMBER
                                env.CHART_NAME = chartYaml.name ?: 'unknown-chart'
                            }
                        }
                    }
                }
            }
        }
    }
        
        stage('Lint Helm Chart') {
            steps {
                container('helm') {
                    dir('charts/ocis') {
                        sh '''
                            helm lint .
                        '''
                    }
                }
            }
        }
        
        stage('Update Dependencies') {
            steps {
                container('helm') {
                    dir('charts/ocis') {
                        sh '''
                            if [ -f "Chart.lock" ]; then
                                helm dependency update
                            fi
                        '''
                    }
                }
            }
        }
        
        stage('Package Helm Chart') {
            steps {
                container('helm') {
                    dir('charts/ocis') {
                        sh '''
                            helm package . \
                                --version ${CHART_VERSION} \
                                --destination ./packaged-charts/
                            
                            # List the packaged chart
                            ls -la ./packaged-charts/
                        '''
                        archiveArtifacts artifacts: 'packaged-charts/*.tgz', fingerprint: true
                    }
                }
            }
        }
        
        stage('Login to Registry') {
            steps {
                container('helm') {
                    withCredentials([usernamePassword(
                        credentialsId: 'ANYMUS-HARBOR-ADMIN',
                        usernameVariable: 'REGISTRY_USERNAME',
                        passwordVariable: 'REGISTRY_PASSWORD'
                    )]) {
                        sh '''
                            echo $REGISTRY_PASSWORD | helm registry login \
                                $(echo $REGISTRY_URL | sed 's|oci://||' | cut -d'/' -f1) \
                                --username $REGISTRY_USERNAME \
                                --password-stdin
                        '''
                    }
                }
            }
        }
        
        stage('Push to OCI Registry') {
            steps {
                container('helm') {
                    dir('charts/ocis') {
                        sh '''
                            # Find the packaged chart
                            CHART_PACKAGE=$(find ./packaged-charts/ -name "*.tgz" -type f)
                            
                            if [ -z "$CHART_PACKAGE" ]; then
                                echo "No chart package found!"
                                exit 1
                            fi
                            
                            echo "Pushing $CHART_PACKAGE to $REGISTRY_URL"
                            helm push "$CHART_PACKAGE" "$REGISTRY_URL"
                            
                            echo "Chart successfully pushed to registry"
                        '''
                    }
                }
            }
        }
        
        stage('Verify Push') {
            steps {
                container('helm') {
                    sh '''
                        # Try to pull the chart to verify it was pushed successfully
                        helm pull ${REGISTRY_URL}/${CHART_NAME} --version ${CHART_VERSION} --destination ./verification/
                        ls -la ./verification/
                    '''
                }
            }
        }
    post {
        always {
            container('helm') {
                sh '''
                    # Logout from registry
                    helm registry logout $(echo $REGISTRY_URL | sed 's|oci://||' | cut -d'/' -f1) || true
                '''
            }
            cleanWs()
        }
        success {
            echo "Helm chart ${CHART_NAME}:${CHART_VERSION} successfully packaged and pushed to ${REGISTRY_URL}"
        }
        failure {
            echo "Pipeline failed. Check the logs for details."
        }
    }
}
