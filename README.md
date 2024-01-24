# Gradle Release Tools

Werkzeuge zum Erzeugen neuer Versionen über CI-Prozesse.
Dieses Tool ist für zwei spezifische Anwendungsfälle gedacht:

Zum Entfernen der *-SNAPSHOT*-Endung aus der *build.gradle*–Datei und Erzeugen eines neuen git-Commits.
Im einfachsten Fall sieht das in der *.gitlab-ci.yml* so aus:

```yaml
.gradle_template:
  image: gradle:8.4-jdk21-jammy
  except:
    - tags
  before_script:
    - export GRADLE_USER_HOME=`pwd`/.gradle
    - mkdir -p $GRADLE_USER_HOME || true
    - cp .gitlab-ci/artifactory-access/init.gradle $GRADLE_USER_HOME

git:release:
  extends: .gradle_template
  only:
    - main
  script:
    # Only perform the branching when this is SNAPSHOT version on the main branch.
    - if [[ ! ($(./gradlew properties | grep version) =~ "SNAPSHOT") ]]; then exit 0; fi
    # Create a new commit on the main branch
    - ./gradlew configureReleaseBot
      removeSnapshotFromVersion
      updateVersionNumberInReadme
      gitCommitForRelease
      createGitTagForVersion
      gitPush
```

Der zweite Verwendungszweck ist das Erzeugen eines neuen Commits auf dem *development*-Branch, mit der nächst-höheren Patch-Nummer und einer Snapshot-Endung (also auf Release *2.0.0* folgt *2.0.1-SNAPSHOT*).
Dies geschieht via

```yaml
git:preparesnapshot:
  extends: .gradle_template
  only:
    - main
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

Die *if*-Zeile bricht die CI-Ausführung ab, wenn sie sich auf eine Snapshot- beziehungsweise nicht-Snapshot–Version bezieht.

Ein vollständiges Minimalbeispiel stellt die [.gitlab-ci.yml](https://code.pagina.gmbh/paginagmbh/gradle-release-tools/-/blob/main/.gitlab-ci.yml) dar.


## Verwendung in Gradle

Dieses Plugin wird einfach mit den anderen Gradle-Plugins aufgeführt.


```groovy
plugins {
	  ...

    id 'de.paginagmbh.commons.gradle-release-tools' version '1.1.1-SNAPSHOT'
}
```

Hierfür muss [Gitlab-CI—Pagina-Artifactory–Access](https://code.pagina.gmbh/paginagmbh/gitlab-ci-pagina-artifactory-access) eingerichtet sein und der Release-Bot als Maintainer im Repo.
Letzteres sollte auf allen code.pagina.‍gmbh-Repos der Fall sein.

Zur Versionsnummer-Ersetzung muss eine zusätzliche Konfigurationsoption angegeben werden.
Siehe dazu [](#updateversionnumberinreadme).


## Tasks

Folgende Tasks werden durch das Tool bereitgestellt:


### configureReleaseBot

Richtet die SSH-Schlüssel des Release-Bot ein und bereitet git-branches so vor, dass sie gepushed werden können.

### removeSnapshotFromVersion

Entfernt die *-SNAPSHOT*-Endung aus der *build.gradle.*


### prepareNextSnapshotVersion

Erhöht die patch-Version in *build.gradle* und fügt die *-SNAPSHOT*-Endung hinzu.
Aus *2.0.0* wird *2.0.1-SNAPSHOT.*


### updateVersionNumberInReadme

Wenn das Readme eine Zeile enthält, die erklärt, wie das Plugin verwendet wird (wie hier in [](#verwendung-in-gradle)), dann wird die Versionsnummer da durch die aktuelle aus der *build.gradle* ersetzt.

Um zu wissen, für welches Plugin die Versionsnummer ersetzt werden soll, muss die ID des Plugins in der *build.gradle* wie folgt angegeben werden:

```groovy
updateVersionNumberInReadme {
    pluginId = "de.paginagmbh.commons.gradle-release-tools"
    // natürlich steht hier die entsprechende ID statt der für dieses Plugin.
}
```

Hier existiert auch die Option `readmeName`, die als Standard *README.md* hat und somit vermutlich nie verändert werden muss.

### gitCommitForRelease

Erzeugt ein git-Commit mit einer Nachricht die angibt, dass es sich hier um das Release der Version handelt.


### gitCommitForSnapshot

Erzeugt ein git-Commit mit einer Nachricht, die angibt, dass es sich hier um die nächste Snapshot-Version handelt.


### switchToDevelopmentAndCatchItUp

Wechselt auf den *development*-Branch und fast-forwarded ihn auf den Stand des *main*-Branches.

:::{attention}
Dieser Task schlägt fehl, wenn der *main*-Branch *master* heißt!
master-Branches sollten umbenannt werden.
:::


### createGitTagForVersion

Erzeugt einen Git-Tag für die Version, die gerade im *build.gradle* festgelegt ist.


### gitPush

Pusht alle Branches und Tags an den Server.


## Todo

- [ ] Handhabung, wenn der development-Branch nicht existiert.
	Es ist GitLab Standard für Repositorien, dass wenn ein Merge-Request abgeschlossen ist, der Quellzweig gelöscht wird.