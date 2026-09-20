package consumertest

import org.junit.platform.suite.api.{Suite, SelectPackages}

// Discovering this suite makes JUnit Platform scan the classpath for classes
// in package `shared` -- which means opening `shared`'s compiled-output jar
// as a java.nio.file.FileSystem.
@Suite
@SelectPackages(Array("shared"))
class RunSharedTests
