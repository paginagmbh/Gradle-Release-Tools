package gmbh.pagina.tools.gradle.release;

import org.gradle.api.Project
import org.gradle.api.Plugin
import org.gradle.api.GradleException

class GradleReleaseTools implements Plugin<Project> {
    void apply(Project project) {
        def buildFile = project.file("build.gradle")
        def versionLineRegex = /version *= *["']((([\d\.]+?)\.(\d+))(-\w+)?)["']/

        // Helper function, executes a shell command
        def sh = { String command ->
            project.exec {
                commandLine 'sh', '-c', command
            }
        }

        project.tasks.register("configureReleaseBot") {
            group 'Release Tools'
            description 'Set up keys and certificates to perform git commits with ReleaseBot'

            doLast {
                // Only run when in docker, it will mess with the system and you don't wanna do that to your server
                if (!new File('/.dockerenv').exists()) {
                    throw new GradleException("This task may only be run inside a Docker container.")
                }

                // SSL Certificates are missing by default in some docker containers
                sh 'apt update -y'
                sh 'apt install ca-certificates -y'
                // Install ssh-agent if not already installed, it is required by Docker.
                // sh 'which ssh-agent || ( apt install openssh-client -y )'

                // Add the SSH key stored in RELEASE_BOT_SSH_PRIVATE_KEY variable to the agent store
                sh 'mkdir -p ~/.ssh'
                sh 'cp $RELEASE_BOT_SSH_PRIVATE_KEY ~/.ssh/id_rsa'
                sh 'chmod 600 ~/.ssh/id_rsa'
                // generate missing private key
                sh 'ssh-keygen -y -f ~/.ssh/id_rsa > ~/.ssh/id_rsa.pub'
                // For Docker builds disable host key checking.
                // Be aware that by adding that you are susceptible to man-in-the-middle attacks.
                // This skips the 'add device fingerprint 12:34:56:78:9a:bc:de:f0 to known hosts?'
                sh 'touch ~/.ssh/config'
                new File("${System.properties['user.home']}/.ssh/config").append('Host *\n\tStrictHostKeyChecking no\n\n')
                // sh 'echo -e -- \'Host *\\n\\tStrictHostKeyChecking no\\n\\n\' > ~/.ssh/config'

                // set username and email for our CI Release Bot
                sh 'git config --global user.name "CI Release Bot"'
                sh 'git config --global user.email "gitlab-release-bot@pagina.gmbh"'
                // set push remote URL for CI user
                sh 'git remote rm origin || true'
                sh "git remote add origin git@code.pagina.gmbh:${System.getenv('CI_PROJECT_PATH')}.git"

                // Actually check out this git branch and make repo pushable
                sh 'git config pull.ff only'
                sh "git fetch --all"
                // Delete the main branch if it exists already
                sh "git branch -D ${System.getenv('CI_COMMIT_REF_NAME')} || true"
                sh "git checkout -t origin/${System.getenv('CI_COMMIT_REF_NAME')}"
                sh "git pull"
            }
        }

        project.tasks.register("switchToMainAndCatchItUp") {
            group 'Release Tools'
            description 'Switch to the main branch and catch it up with the current branch.'

            doLast {
                // Delete the main branch if it exists already
                sh "git branch -D ${System.getenv('CI_DEFAULT_BRANCH')} || true"
                // Clone the main branch from origin
                sh 'git fetch'
                sh "git checkout -t origin/${System.getenv('CI_DEFAULT_BRANCH')}"
                sh 'git pull'
                // Merge into last branch
                sh 'git merge -' // --ff-only
            }
        }

        project.tasks.register("switchToDevelopmentAndCatchItUp") {
            group 'Release Tools'
            description 'Switch to the development branch and catch it up with main.'

            doLast {
                // Delete the development branch if it exists already
                sh 'git branch -D development || true'
                // Clone the development branch from origin
                sh 'git checkout -t origin/development'
                sh 'git pull'
                // Merge into main branch
                sh "git merge ${System.getenv('CI_DEFAULT_BRANCH')}" // --ff-only
            }
        }

        project.tasks.register("removeSnapshotFromVersion") {
            group 'Release Tools'
            description 'Remove the -SNAPSHOT suffix from the version number in build.gradle.'
            doLast {
                buildFile.write(buildFile.getText().replaceAll(versionLineRegex, 'version = "$2"'))
            }
        }

        project.tasks.register("prepareNextSnapshotVersion") {
            shouldRunAfter project.tasks.findByName("switchToDevelopmentAndCatchItUp")
            shouldRunAfter project.tasks.findByName("switchToMainAndCatchItUp")

            group 'Release Tools'
            description 'Increment the patch number and add a -SNAPSHOT suffix if it does not exist.'

            doLast {
                def text = buildFile.getText()
                def matcher = text =~ versionLineRegex
                def majorMinorVersion = matcher[0][3]
                def patch = Integer.parseInt(matcher[0][4])
                buildFile.write(text.replaceAll(versionLineRegex, "version = \"${majorMinorVersion}.${patch + 1}-SNAPSHOT\""))
            }
        }

        project.tasks.register("updateVersionNumberInReadme") {
            shouldRunAfter project.tasks.findByName("switchToDevelopmentAndCatchItUp")
            shouldRunAfter project.tasks.findByName("switchToMainAndCatchItUp")
            shouldRunAfter project.tasks.findByName("removeSnapshotFromVersion")
            shouldRunAfter project.tasks.findByName("prepareNextSnapshotVersion")

            group 'Release Tools'
            description 'Changes the version number in the Readme for the sample showing how to use the plugin.'

            ext {
                pluginId = "$project.group.$project.name"
                readmeName = "README.md"
            }

            doLast {
                def version = (buildFile.getText() =~ versionLineRegex)[0][1]
                def readme = project.file(readmeName)
                readme.write(
                    readme.getText()
                    .replaceAll(
                        /(['"]${pluginId}['"]\s+version\s+['"])[^']+(['"])/,
                        '$1' + "${version}" + '$2'
                    )
                    .replaceAll(
                        /(['"]${pluginId}:)[^'"@]+((@[^'"]+)?['"])/,
                        '$1' + "${version}" + '$2'
                    )
                )
            }
        }

        project.tasks.register("gitCommitForRelease") {
            shouldRunAfter project.tasks.findByName("switchToDevelopmentAndCatchItUp")
            shouldRunAfter project.tasks.findByName("switchToMainAndCatchItUp")
            shouldRunAfter project.tasks.findByName("removeSnapshotFromVersion")
            shouldRunAfter project.tasks.findByName("prepareNextSnapshotVersion")
            shouldRunAfter project.tasks.findByName("updateVersionNumberInReadme")

            group 'Release Tools'
            description 'Commits all changed files with a note that a release occured.'

            doLast {
                def version = (buildFile.getText() =~ versionLineRegex)[0][1]
                sh "git commit --allow-empty -a -m '[grt] release v${version}' -m 'For the source code of the release bot see https://github.com/paginagmbh/Gradle-Release-Tools'"
            }
        }

        project.tasks.register("gitCommitForSnapshot") {
            shouldRunAfter project.tasks.findByName("switchToDevelopmentAndCatchItUp")
            shouldRunAfter project.tasks.findByName("switchToMainAndCatchItUp")
            shouldRunAfter project.tasks.findByName("removeSnapshotFromVersion")
            shouldRunAfter project.tasks.findByName("prepareNextSnapshotVersion")
            shouldRunAfter project.tasks.findByName("updateVersionNumberInReadme")

            group 'Release Tools'
            description 'Commits all changed files with a note that this is the next development version.'

            doLast {
                def version = (buildFile.getText() =~ versionLineRegex)[0][1]
                sh "git commit --allow-empty -a -m '[grt] prepare for next development iteration (v${version})' -m 'For the source code of the release bot see https://github.com/paginagmbh/Gradle-Release-Tools'"
            }
        }

        project.tasks.register("createGitTagForVersion") {
            shouldRunAfter project.tasks.findByName("switchToDevelopmentAndCatchItUp")
            shouldRunAfter project.tasks.findByName("switchToMainAndCatchItUp")
            shouldRunAfter project.tasks.findByName("removeSnapshotFromVersion")
            shouldRunAfter project.tasks.findByName("prepareNextSnapshotVersion")
            shouldRunAfter project.tasks.findByName("updateVersionNumberInReadme")
            shouldRunAfter project.tasks.findByName("gitCommitForRelease")
            shouldRunAfter project.tasks.findByName("gitCommitForSnapshot")

            group 'Release Tools'
            description 'Creates a git tag for the current version – including SNAPSHOT'

            doLast {
                def version = (buildFile.getText() =~ versionLineRegex)[0][1]
                sh "git tag -f 'v${version}'"
            }
        }

        project.tasks.register("gitPush") {
            shouldRunAfter project.tasks.findByName("switchToDevelopmentAndCatchItUp")
            shouldRunAfter project.tasks.findByName("switchToMainAndCatchItUp")
            shouldRunAfter project.tasks.findByName("gitCommitForRelease")
            shouldRunAfter project.tasks.findByName("gitCommitForSnapshot")

            group 'Release Tools'
            description 'Pushes all branches and all tags'

            doLast {
                sh 'git push --all --verbose'
                sh 'git push -f --tags'
            }
        }
    }
}
