def sendLog (errorStage) {
  pckJson = readJSON file: './package.json'
  def ciName = "http://jci.onelaas.com"
  sh "curl -H \"Content-Type:application/json\" -X PUT --data '{ \"name\": \"${pckJson.name}\", \"version\": \"${pckJson.version}\", \"status\": \"${errorStage}\", \"ciName\": \"${ciName}\", \"ciJobName\": \"${JOB_NAME}\", \"ciJobNumber\": ${BUILD_NUMBER} }' http://182.92.157.77:7001/api/v3/ci/builds"
}

wrap([ $class: 'TimestamperBuildWrapper' ]) {

  stage ('git clone') {
    git url: "${params.gitcloneurl}", branch: "${params.branch}"  
  }

  stage ('define var') {
    pckJson = readJSON file: './package.json'
    safeCmpName = pckJson.name.replace("@","").replace("/", "-")
    shortCommit = sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
  }

  stage ('recording') {
    sendLog("building") // 记录构建信息，用于在Center中查看
  }

  stage ('npm install') {
    sh 'npm cache verify'
    retry(2) {
      sh 'npm install'
    }
  }

  parallel (
    'npm run build:*': {
      stage ('npm run build:*') {
        sh "npm run build:*" // 执行 package.json 中定义的 build:* 命令
      }
    },
    'build stroybook': {
      stage ('build stroybook') {
        sh 'npm run storybook-build' // 执行标准规范的 storybook 编译
      }
    },
  )

  parallel (
    'center': {
      stage ('center') {
        def assets = "--assets /${safeCmpName}/release/dist/main-COMMIT_ID-${pckJson.version}\\.min\\.js"
        def example = "--example-path /${safeCmpName}/${pckJson.version}/iframe\\.html\\?selectedKind=${safeCmpName}\\&selectedStory=EXAMPLE_NAME"
        def gitssh = "--gitssh ${params.gitcloneurl}"
        def commitId = "--commit-id '${shortCommit}'"
        sh "cmp-ci publish ${assets} ${example} ${gitssh} ${commitId} ${tenantId} ${userId} --host http://182.92.157.77:7001/api/v3/components"
      }
    }
  )

  parallel (
    'oss storybook': {
      stage ('oss storybook') {
        sh '/home/jenkins-slave/ossutil64 cp -r -u ./storybook-static oss://cmpter/ux/storybook/ --config-file /home/jenkins-slave/.ossutilconfig'
      }
    },
    'oss dist': {
      stage ('oss dist') {
        sh '/home/jenkins-slave/ossutil64 cp -r -u ./dist oss://cmpter/ux/runtime/ --config-file /home/jenkins-slave/.ossutilconfig'
      }
    },
    'npm publish': {
      stage ('npm publish') {
        sh "npm publish --access public"
      }
    }
  )
}
