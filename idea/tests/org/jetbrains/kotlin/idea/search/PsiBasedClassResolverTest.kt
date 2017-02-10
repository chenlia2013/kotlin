/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.search

import com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCaseBase
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.junit.Assert

class PsiBasedClassResolverTest : KotlinLightCodeInsightFixtureTestCaseBase() {
    fun testSimple() {
        Assert.assertFalse(doTest("val c = Test()")!!)
    }

    fun testImported() {
        Assert.assertTrue(doTest("import org.junit.Test\n\nval c = Test()")!!)
    }

    fun testImportStar() {
        Assert.assertTrue(doTest("import org.junit.*\n\nval c = Test()")!!)
    }

    fun testImportConflict() {
        Assert.assertFalse(doTest("import org.testng.Test\n\nval c = Test()")!!)
    }

    fun testImportStarConflict() {
        Assert.assertFalse(doTest("import org.testng.*\n\nval c = Test()")!!)
    }

    fun testImportBoth() {
        Assert.assertNull(doTest("import org.testng.*\nimport org.junit.Test\nval c = Test()"))
    }

    fun testImportBoth2() {
        Assert.assertNull(doTest("import org.testng.Test\nimport org.junit.*\nval c = Test()"))
    }

    fun testImportAs() {
        Assert.assertNull(doTest("import org.junit.*\nimport java.lang.String as Test\nval c = Test()"))
    }

    fun testThisPackage() {
        Assert.assertTrue(doTest("package org.junit\n\nval c = Test()")!!)
    }

    fun testConflictPackageAmbiguity() {
        Assert.assertNull(doTest("package org.testng\n\nimport org.junit.Test\n\nval c = Test()"))
    }

    fun testFqName() {
        Assert.assertNull(doTest("val c = org.junit.Test()"))
    }

    fun testFqNameAnn() {
        Assert.assertNull(doTest("@org.junit.Test() fun foo() { }"))
    }

    fun testFqNameWithConflictingImport() {
        Assert.assertNull(doTest("import org.testng.Test\n\nval c = org.junit.Test()"))
    }

    private fun doTest(fileText: String): Boolean? {
        val resolver = PsiBasedClassResolver("org.junit.Test")
        resolver.addConflict("org.testng.Test")
        val file = PsiFileFactory.getInstance(project).createFileFromText("foo.kt", KotlinFileType.INSTANCE,
                                                                          fileText) as KtFile
        val index = fileText.indexOf("Test()")
        val ref = file.findElementAt(index)!!.getParentOfType<KtSimpleNameExpression>(false)!!
        return resolver.canBeTargetReference(ref)
    }
}
