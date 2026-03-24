# Gradle Release Tools

Tools for creating new versions through CI processes.
It is specifically designed for **GitLab CI.**
This tool is intended for two specific use cases:

To remove the *-SNAPSHOT* suffix from the *build.gradle* file and create a new git commit.
In the simplest case, this looks like this in *.gitlab-ci.yml*:

```yaml
stages:
  - deploy
  - release

.gradle_template:
  image: gradle:8.4-jdk21-jammy

git:release:
  extends: .gradle_template
  stage: release
  rules:
    - when: manual
  script:
    # Create a new commit on the main branch
    - ./gradlew configureReleaseBot
      switchToMainAndCatchItUp
      removeSnapshotFromVersion
      updateVersionNumberInReadme
      gitCommitForRelease
      createGitTagForVersion
      gitPush
```

The second use case is creating a new commit on the *development* branch with the next higher patch number and a snapshot suffix (so release *2.0.0* is followed by *2.0.1-SNAPSHOT*).
This is done via

```yaml
git:preparesnapshot:
  extends: .gradle_template
  stage: release
  rules:
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
  script:
    # Only perform the branching when this is not a SNAPSHOT version on the main branch.
    - if [[ ($(./gradlew properties | grep version) =~ "SNAPSHOT") ]]; then exit 0; fi
    # Create a new commit on the development branch for the next version
    - ./gradlew configureReleaseBot
      switchToDevelopmentAndCatchItUp
      prepareNextSnapshotVersion
      updateVersionNumberInReadme
      gitCommitForSnapshot
      gitPush
```

The release is then done by manually running the job in the development pipeline.

The *if* line aborts CI execution depending on whether it refers to a snapshot or non-snapshot version.

A complete minimal example is provided by [.gitlab-ci.yml](.gitlab-ci.yml).


## Usage in Gradle

This plugin is simply listed together with the other Gradle plugins.


```groovy
plugins {
	  ...

    id 'de.paginagmbh.tools.gradle.release' version '1.2.0'
}
```

To use and compile the plugin, a token for [the Maven package source from GitLab](https://code.pagina.gmbh/paginagmbh/maven-registry) must be configured.

For version number replacement, an additional configuration option must be specified.
See [here](#updateversionnumberinreadme).


## Tasks

The following tasks are provided by this tool:


### configureReleaseBot

Sets up the release bot SSH keys and prepares git branches so they can be pushed.


### removeSnapshotFromVersion

Removes the *-SNAPSHOT* suffix from *build.gradle.*


### prepareNextSnapshotVersion

Increases the patch version in *build.gradle* and adds the *-SNAPSHOT* suffix.
*2.0.0* becomes *2.0.1-SNAPSHOT.*


### updateVersionNumberInReadme

If the README contains a line that explains how the plugin is used (as shown [here](#usage-in-gradle)), then the version number there is replaced with the current one from *build.gradle*.

To know for which plugin the version number should be replaced, the plugin ID must be specified in *build.gradle* as follows:

```groovy
updateVersionNumberInReadme {
    pluginId = "de.paginagmbh.tools.gradle.release"
    // naturally, use the corresponding ID here instead of the one for this plugin.
}
```

By default, `<group>.<project-name>` is assumed here, so this block is not strictly required.

There is also the `readmeName` option, which has *README.md* as the default and therefore probably never needs to be changed.


### gitCommitForRelease

Creates a git commit with a message indicating that this is the release of the version.


### gitCommitForSnapshot

Creates a git commit with a message indicating that this is the next snapshot version.


### switchToMainAndCatchItUp

Switches to the *main* branch and fast-forwards it to the state of the current branch.
The main branch is determined by the `CI_DEFAULT_BRANCH` variable, which is set by GitLab CI to the default branch of the repository (usually *main* or *master*).


### switchToDevelopmentAndCatchItUp

Switches to the *development* branch and fast-forwards it to the state of the *main* branch.
The main branch is determined by the `CI_DEFAULT_BRANCH` variable, which is set by GitLab CI to the default branch of the repository (usually *main* or *master*).


### createGitTagForVersion

Creates a Git tag for the version currently set in *build.gradle*.


### gitPush

Pushes all branches and tags to the server.


## Todo

- [ ] Handling when the development branch does not exist.
	It is the GitLab default for repositories that when a merge request is completed, the source branch is deleted.
