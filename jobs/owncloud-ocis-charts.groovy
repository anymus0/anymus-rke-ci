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
        REGISTRY_HOST = 'harbor-core.harbor'
        CHART_NAME = 'ocis'
        CHART_VERSION = "${BUILD_NUMBER}"
    }
    
    stages {
        stage('Clone Helm Chart Repository') {
            steps {
                container('git') {
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

        stage('Prepare Values for Linting') {
            steps {
                container('helm') {
                    dir('charts/ocis') {
                        script {
                            echo "Updating values.yaml to set externalDomain..."
                            // Read the values.yaml file into a Groovy object
                            def values = readYaml file: 'values.yaml' ?: [:]

                            // Overwrite or set the externalDomain value
                            values.externalDomain = 'example.com'
                            
                            // Write the modified object back to the file
                            writeYaml file: 'values.yaml', data: values, overwrite: true
                            
                            echo "Successfully set externalDomain to 'example.com'"
                        }
                    }
                }
            }
        }
        
        stage('Lint Helm Chart') {
            steps {
                container('helm') {
                    dir('charts/ocis') {
                        sh 'helm lint .'
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
                            helm package . --version ${CHART_VERSION} --destination ./packaged-charts/
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
                            echo $REGISTRY_PASSWORD | helm registry login $REGISTRY_HOST \
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
                    sh '''
                        CHART_PACKAGE=$(find ./charts/ocis/packaged-charts/ -name "*.tgz" -type f)
                        
                        if [ -z "$CHART_PACKAGE" ]; then
                            echo "No chart package found!"
                            exit 1
                        fi
                        
                        echo "Pushing $CHART_PACKAGE to $REGISTRY_URL"
                        # Add --insecure-registry flag for HTTP registries
                        helm push "$CHART_PACKAGE" "$REGISTRY_URL" --insecure-registry
                    '''
                }
            }
        }
        
        stage('Verify Push') {
            steps {
                container('helm') {
                    sh '''
                        # Corrected pull command for OCI and added --insecure-registry
                        helm pull "${REGISTRY_URL}/${CHART_NAME}" --version ${CHART_VERSION} --insecure-registry
                        ls -la
                    '''
                }
            }
        }
    }
    
    post {
        always {
            container('helm') {
                sh 'helm registry logout $REGISTRY_HOST || true'
            }
            cleanWs()
        }
        success {
            echo "Helm chart ${CHART_NAME}:${CHART_VERSION} successfully pushed to ${REGISTRY_URL}"
        }
        failure {
            echo "Pipeline failed. Check the logs for details."
        }
    }
}
