Unlike the rest of the modules, this is a standalone Gradle 6.0+ project, because
it uses shadowJar, which is not compatible with Gradle 5.4.1 that Corda requries.

The purpose of this module is to build a dependency library that shadows javax.json 1.1
implementation to another package to avoid clashing with version 1.0 bundled into Corda.

All versions of Corda up until 4.6 (latest at the time of writing) bundle in javax.json 1.0,
so this will need to be supported until everyone upgrades to much newer version.
