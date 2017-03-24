// Enable IMCE SBT plugin - an aggregate of useful SBT plugins and common functionality
resolvers += Resolver.bintrayRepo("jpl-imce", "gov.nasa.jpl.imce")
addSbtPlugin("gov.nasa.jpl.imce" % "imce.sbt.plugin" % "4.20.0")
