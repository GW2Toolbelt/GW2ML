/*
 * Copyright (c) 2019-2020 Leon Linhart
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.github.gw2toolbelt.build.tasks

import org.gradle.api.*
import org.gradle.api.tasks.*
import java.io.*

@CacheableTask
open class GenerateOpenModuleInfo : DefaultTask() {

    @Input
    lateinit var moduleName: String

    @Input
    var body: String? = null

    @OutputFile
    val outputFile: File = File(project.buildDir, "generateOpenModuleInfo/$name/src/module-info.java")

    @TaskAction
    fun run() {
        outputFile.delete()
        outputFile.writeText("open module $moduleName {${if (body.isNullOrEmpty()) "" else "\n\n    $body\n\n"}}")
    }

}