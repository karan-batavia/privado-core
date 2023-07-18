/*
 * This file is part of Privado OSS.
 *
 * Privado is an open source static code analysis tool to discover data flows in the code.
 * Copyright (C) 2022 Privado, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * For more information, contact support@privado.ai
 *
 */

package ai.privado.utility

import ai.privado.utility.Utilities.getAllFilesRecursivelyWithoutExtension
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import better.files.File
import scala.collection.mutable

class UtilitiesTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  private val inputDirs = mutable.ArrayBuffer.empty[File]

  "getDomainFromTemplates test" should {
    "domain extratcion sample one" in {
      val code =
        """<Script
      id="amazon-tag"
      data-testid="amazon-tag"
      src="https://c.amazon-adsystem.com/aax2/apstag.js"
      strategy="lazyOnload"
    />"""
      val domain = Utilities.getDomainFromTemplates(code)
      domain._2 shouldBe "c.amazon-adsystem.com"
      domain._1 shouldBe "https://c.amazon-adsystem.com/aax2/apstag.js"
    }
    "domain extratcion sample two" in {
      val code =
        """<Script
        id="googletag-script"
        data-testid="googletag-script"
        src="https://www.googletagservices.com/tag/js/gpt.js"
        strategy="lazyOnload"
      />"""
      val domain = Utilities.getDomainFromTemplates(code)
      domain._2 shouldBe "googletagservices.com"
      domain._1 shouldBe "https://www.googletagservices.com/tag/js/gpt.js"
    }
    "domain extratcion sample three" in {
      val code =
        """<Script
    id="googletag-script"
    data-testid="googletag-script"
    src="//www.googletagservices.com/tag/js/gpt.js"
    strategy="lazyOnload"
  />"""
      val domain = Utilities.getDomainFromTemplates(code)
      domain._2 shouldBe "googletagservices.com"
      domain._1 shouldBe "//www.googletagservices.com/tag/js/gpt.js"
    }

    "domain extratcion sample four" in {
      val code =
        """<Script
        id="prebid-script"
        data-testid="prebid-script"
        src={`${CDN_ADS_URL}/prebid.js`}
        strategy="lazyOnload"
        />"""
      val domain = Utilities.getDomainFromTemplates(code)
      domain._2 shouldBe "unknown-domain"
      domain._1 shouldBe "unknown-domain"
    }
  }

  "getAllFilesRecursivelyWithoutExtension" should {
    "return the list of files recursively without extension" in {
      val testFolderPath = getDirectoryPath("--", "Gemfile")
      val expectedFiles  = List(s"$testFolderPath/Gemfile")

      val result = getAllFilesRecursivelyWithoutExtension(testFolderPath, "Gemfile")
      result shouldBe Some(expectedFiles)
    }

    "return None when the folder path is invalid" in {
      val invalidFolderPath = "path/to/invalid/folder"

      val result = getAllFilesRecursivelyWithoutExtension(invalidFolderPath, "Gemfile")
      result shouldBe None
    }

    "return an empty list when no files are found" in {
      val emptyFolderPath = getDirectoryPath("--", "package.json")

      val result = getAllFilesRecursivelyWithoutExtension(emptyFolderPath, "Gemfile")
      result shouldBe Some(List.empty)
    }
  }

  def getDirectoryPath(code: String, fileName: String): String = {
    val inputDir = File.newTemporaryDirectory()
    inputDirs.addOne(inputDir)
    (inputDir / fileName).write(code)
    inputDir.toString()
  }
}
