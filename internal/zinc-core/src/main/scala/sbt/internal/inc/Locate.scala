/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Lightbend, Inc. and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt
package internal
package inc

import java.nio.file.{ Files, Path }
import java.util.zip.{ ZipException, ZipFile }
import xsbti.{ PathBasedFile, VirtualFile }
import xsbti.compile.{ DefinesClass, PerClasspathEntryLookup }

object Locate {

  /**
   * Right(src) provides the value for the found class
   * Left(true) means that the class was found, but it had no associated value
   * Left(false) means that the class was not found
   */
  def value[S](
      classpath: Seq[VirtualFile],
      get: VirtualFile => String => Option[S]
  ): String => Either[Boolean, S] = {
    val gets = classpath.toStream.map(getValue(get))
    className => find(className, gets)
  }

  def find[S](name: String, gets: Stream[String => Either[Boolean, S]]): Either[Boolean, S] =
    if (gets.isEmpty)
      Left(false)
    else
      gets.head(name) match {
        case Left(false) => find(name, gets.tail)
        case x           => x
      }

  /**
   * Returns a function that searches the provided class path for
   * a class name and returns the entry that defines that class.
   */
  def entry(
      classpath: Seq[VirtualFile],
      lookup: PerClasspathEntryLookup
  ): String => Option[VirtualFile] = {
    val entries = classpath.toStream.map { entry =>
      (entry, lookup.definesClass(entry))
    }
    className => entries.collectFirst { case (entry, defines) if defines(className) => entry }
  }

  def getValue[S](
      get: VirtualFile => String => Option[S]
  )(entry: VirtualFile): String => Either[Boolean, S] = {
    val defClass = definesClass(entry)
    val getF = get(entry)
    className => if (defClass(className)) getF(className).toRight(true) else Left(false)
  }

  def definesClass(entry0: VirtualFile): DefinesClass =
    entry0 match {
      case x: PathBasedFile =>
        val entry = x.toPath
        if (Files.isDirectory(entry))
          new DirectoryDefinesClass(entry)
        else if (Files.exists(entry) && classpath.ClasspathUtil.isArchive(
                   entry,
                   contentFallback = true
                 ))
          new JarDefinesClass(entry)
        else
          FalseDefinesClass
      case _ =>
        sys.error(s"$entry0 (${entry0.getClass}) is not supported")
    }

  private object FalseDefinesClass extends DefinesClass {
    override def apply(binaryClassName: String): Boolean = false
  }

  private class JarDefinesClass(entry: Path) extends DefinesClass {
    import collection.JavaConverters._
    private val entries = {
      val jar = try {
        new ZipFile(entry.toFile, ZipFile.OPEN_READ)
      } catch {
        // ZipException doesn't include the file name :(
        case e: ZipException =>
          throw new RuntimeException("Error opening zip file: " + entry.getFileName.toString, e)
      }
      try {
        jar.entries.asScala.map(e => toClassName(e.getName)).toSet
      } finally {
        jar.close()
      }
    }
    override def apply(binaryClassName: String): Boolean =
      entries.contains(binaryClassName)
  }

  def toClassName(entry: String): String =
    entry.stripSuffix(ClassExt).replace('/', '.')

  val ClassExt = ".class"

  private class DirectoryDefinesClass(entry: Path) extends DefinesClass {
    override def apply(binaryClassName: String): Boolean =
      Files.isRegularFile(classFile(entry, binaryClassName))
  }

  def classFile(baseDir: Path, className: String): Path = {
    val (pkg, name) = components(className)
    val dir = subDirectory(baseDir, pkg)
    dir.resolve(name + ClassExt)
  }

  def subDirectory(base: Path, parts: Seq[String]): Path =
    parts.foldLeft(base)((b, p) => b.resolve(p))

  def components(className: String): (Seq[String], String) = {
    assume(!className.isEmpty)
    val parts = className.split("\\.")
    if (parts.length == 1) (Nil, parts(0)) else (parts.init, parts.last)
  }
}
