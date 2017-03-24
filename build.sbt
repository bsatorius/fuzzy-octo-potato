import sbt.Keys._
import sbt._
import scala.io.Source

import spray.json._, DefaultJsonProtocol._

import complete.DefaultParsers._

import scala.language.postfixOps
import gov.nasa.jpl.imce.sbt._
import gov.nasa.jpl.imce.sbt.ProjectHelper._
import java.io.File

//Speed up build time
updateOptions := updateOptions.value.withCachedResolution(true)

//Use local maven repo if uncommitted changes exist
resolvers ++= {
  if (git.gitUncommittedChanges.value)
    Seq[Resolver](Resolver.mavenLocal)
  else
    Seq.empty[Resolver]
}

shellPrompt in ThisBuild := { state => Project.extract(state).currentRef.project + "> " }

//Utility function
// @see https://github.com/jrudolph/sbt-dependency-graph/issues/113
def zipFileSelector
( a: Artifact, f: File)
: Boolean
= a.`type` == "zip" || a.extension == "zip"

//Check if necessary
// @see https://github.com/jrudolph/sbt-dependency-graph/issues/113
def fromConfigurationReport
(report: ConfigurationReport,
 rootInfo: sbt.ModuleID,
 selector: (Artifact, File) => Boolean)
: net.virtualvoid.sbt.graph.ModuleGraph = {
  implicit def id(sbtId: sbt.ModuleID): net.virtualvoid.sbt.graph.ModuleId
  = net.virtualvoid.sbt.graph.ModuleId(sbtId.organization, sbtId.name, sbtId.revision)

  def moduleEdges(orgArt: OrganizationArtifactReport)
  : Seq[(net.virtualvoid.sbt.graph.Module, Seq[net.virtualvoid.sbt.graph.Edge])]
  = {
    val chosenVersion = orgArt.modules.find(!_.evicted).map(_.module.revision)
    orgArt.modules.map(moduleEdge(chosenVersion))
  }

  def moduleEdge(chosenVersion: Option[String])(report: ModuleReport)
  : (net.virtualvoid.sbt.graph.Module, Seq[net.virtualvoid.sbt.graph.Edge]) = {
    val evictedByVersion = if (report.evicted) chosenVersion else None

    val jarFile = report.artifacts.find(selector.tupled).map(_._2)
    (net.virtualvoid.sbt.graph.Module(
      id = report.module,
      license = report.licenses.headOption.map(_._1),
      evictedByVersion = evictedByVersion,
      jarFile = jarFile,
      error = report.problem),
      report.callers.map(caller ⇒ net.virtualvoid.sbt.graph.Edge(caller.caller, report.module)))
  }

  val (nodes, edges) = report.details.flatMap(moduleEdges).unzip
  val root = net.virtualvoid.sbt.graph.Module(rootInfo)

  net.virtualvoid.sbt.graph.ModuleGraph(root +: nodes, edges.flatten)
}

//val projectIdAttributes = if ("briansart" == "")
//  "build.date.utc" -> buildUTCDate.value
//else {
//  ("build.date.utc" -> buildUTCDate.value,
//    "artifact.kind" -> "briansart")
//}

//Look into using giter string conversion functions
//Remove all hard coded strings
lazy val core =
  Project("myProjectName".replace(".","-"), file("."))
    .enablePlugins(IMCEGitPlugin)
    .enablePlugins(IMCEReleasePlugin)
    .settings(dynamicScriptsResourceSettings("myProjectName"))
    .settings(IMCEPlugin.strictScalacFatalWarningsSettings)
    .settings(IMCEReleasePlugin.packageReleaseProcessSettings)
    .settings(
      releaseProcess := Seq(
      IMCEReleasePlugin.clearSentinel,
      sbtrelease.ReleaseStateTransformations.checkSnapshotDependencies,
      sbtrelease.ReleaseStateTransformations.inquireVersions,
      IMCEReleasePlugin.extractStep,
      IMCEReleasePlugin.setReleaseVersion,
      IMCEReleasePlugin.runCompile,
      sbtrelease.ReleaseStateTransformations.tagRelease,
      sbtrelease.ReleaseStateTransformations.publishArtifacts,
      sbtrelease.ReleaseStateTransformations.pushChanges,
      IMCEReleasePlugin.successSentinel
    ),

      IMCEKeys.licenseYearOrRange := "2016",
      IMCEKeys.organizationInfo := IMCEPlugin.Organizations.omf,
      IMCEKeys.targetJDK := IMCEKeys.jdk18.value,

      buildInfoPackage := "myProjectName",
      buildInfoKeys ++= Seq[BuildInfoKey](BuildInfoKey.action("buildDateUTC") { buildUTCDate.value }),

      scalacOptions in (Compile,doc) ++= Seq(
        "-diagrams",
        "-doc-title", name.value,
        "-doc-root-content", baseDirectory.value + "/rootdoc.txt"),

      projectID := {
        val previous = projectID.value
        previous.extra("build.date.utc" -> buildUTCDate.value,
            "artifact.kind" -> "briansart")
      },

      IMCEKeys.targetJDK := IMCEKeys.jdk18.value,

      //Include jar unit tests
      publishArtifact in Test := true,

      //Related to MD and how we handle dependencies
      unmanagedClasspath in Compile ++= (unmanagedJars in Compile).value,

      //Bintray resolvers
      resolvers += Resolver.bintrayRepo("jpl-imce", "gov.nasa.jpl.imce"),
      resolvers += Resolver.bintrayRepo("tiwg", "org.omg.tiwg"),

      //Supersafe setup
      resolvers += "Artima Maven Repository" at "http://repo.artima.com/releases", //Redundant to supersafe.sbt line #1???
      scalacOptions in (Compile, compile) += s"-P:artima-supersafe:config-file:${baseDirectory.value}/project/supersafe.cfg",
      scalacOptions in (Test, compile) += s"-P:artima-supersafe:config-file:${baseDirectory.value}/project/supersafe.cfg",
      scalacOptions in (Compile, doc) += "-Xplugin-disable:artima-supersafe",
      scalacOptions in (Test, doc) += "-Xplugin-disable:artima-supersafe",

      scalaSource in Test := baseDirectory.value / "src" / "test" / "scala"
    )

def dynamicScriptsResourceSettings(projectName: String): Seq[Setting[_]] = {

  import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport._

  def addIfExists(f: File, name: String): Seq[(File, String)] =
    if (!f.exists) Seq()
    else Seq((f, name))

  val QUALIFIED_NAME = "^[a-zA-Z][\\w_]*(\\.[a-zA-Z][\\w_]*)*$".r

  Seq(
    // the '*-resource.zip' archive will start from: 'dynamicScripts'
    com.typesafe.sbt.packager.Keys.topLevelDirectory in Universal := None,

    // name the '*-resource.zip' in the same way as other artifacts
    com.typesafe.sbt.packager.Keys.packageName in Universal :=
      normalizedName.value + "_" + scalaBinaryVersion.value + "-" + version.value + "-resource",

    // contents of the '*-resource.zip' to be produced by 'universal:packageBin'
    mappings in Universal ++= {
      val dir = baseDirectory.value
      val bin = (packageBin in Compile).value
      val src = (packageSrc in Compile).value
      val doc = (packageDoc in Compile).value
      val binT = (packageBin in Test).value
      val srcT = (packageSrc in Test).value
      val docT = (packageDoc in Test).value

      (dir * ".classpath").pair(rebase(dir, projectName)) ++
        (dir * "*.md").pair(rebase(dir, projectName)) ++
        (dir / "resources" ***).pair(rebase(dir, projectName)) ++
        addIfExists(bin, projectName + "/lib/" + bin.name) ++
        addIfExists(binT, projectName + "/lib/" + binT.name) ++
        addIfExists(src, projectName + "/lib.sources/" + src.name) ++
        addIfExists(srcT, projectName + "/lib.sources/" + srcT.name) ++
        addIfExists(doc, projectName + "/lib.javadoc/" + doc.name) ++
        addIfExists(docT, projectName + "/lib.javadoc/" + docT.name)
    },

    artifacts += {
      val n = (name in Universal).value
      Artifact(n, "zip", "zip", Some("resource"), Seq(), None, Map())
    },
    packagedArtifacts += {
      val p = (packageBin in Universal).value
      val n = (name in Universal).value
      Artifact(n, "zip", "zip", Some("resource"), Seq(), None, Map()) -> p
    }
  )
}