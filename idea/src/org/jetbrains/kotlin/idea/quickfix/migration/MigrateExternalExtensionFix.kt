/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.quickfix.migration

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.annotations.checkAnnotationName
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.intentions.SpecifyTypeExplicitlyIntention
import org.jetbrains.kotlin.idea.project.builtIns
import org.jetbrains.kotlin.idea.quickfix.CleanupFix
import org.jetbrains.kotlin.idea.quickfix.KotlinQuickFixAction
import org.jetbrains.kotlin.idea.quickfix.KotlinSingleIntentionActionFactory
import org.jetbrains.kotlin.idea.util.addAnnotation
import org.jetbrains.kotlin.js.PredefinedAnnotation
import org.jetbrains.kotlin.js.resolve.diagnostics.ErrorsJs
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode

class MigrateExternalExtensionFix(declaration: KtNamedDeclaration)
    : KotlinQuickFixAction<KtNamedDeclaration>(declaration), CleanupFix {

    override fun getText() = "Fix with 'asDynamic'"
    override fun getFamilyName() = text

    override fun invoke(project: Project, editor: Editor?, file: KtFile) {
        val declaration = element ?: return
        if (isExternalMemberDeclaration(declaration)) {
            fixExtensionMemberDeclaration(declaration, project, editor, file)
            return
        }
    }

    private fun fixExtensionMemberDeclaration(declaration: KtNamedDeclaration, project: Project, editor: Editor?, file: KtFile) {
        val name = declaration.nameAsSafeName
        val annotationEntries = declaration.modifierList?.annotationEntries
        val isGetter = annotationEntries?.any { it.isJsAnnotation(PredefinedAnnotation.NATIVE_GETTER) } ?: false
        val isSetter = annotationEntries?.any { it.isJsAnnotation(PredefinedAnnotation.NATIVE_SETTER) } ?: false
        val isInvoke = annotationEntries?.any { it.isJsAnnotation(PredefinedAnnotation.NATIVE_INVOKE) } ?: false
        annotationEntries?.filter { it.isJsNativeAnnotation() }?.forEach { it.delete() }
        declaration.addModifier(KtTokens.INLINE_KEYWORD)
        declaration.removeModifier(KtTokens.EXTERNAL_KEYWORD)
        if (declaration is KtFunction) {
            declaration.addAnnotation(KotlinBuiltIns.FQ_NAMES.suppress.toSafe(), "\"NOTHING_TO_INLINE\"")
            if (!declaration.hasDeclaredReturnType() && !isSetter && !isInvoke) {
                SpecifyTypeExplicitlyIntention.addTypeAnnotation(editor, declaration, declaration.builtIns.unitType)
            }
        }

        val ktPsiFactory = KtPsiFactory(project)
        val body = ktPsiFactory.buildExpression {
            appendName(Name.identifier("asDynamic"))
            if (isGetter) {
                appendFixedText("()")
                if (declaration is KtNamedFunction) {
                    appendParameters(declaration, "[", "]")
                }
            } else if (isSetter) {
                appendFixedText("()")
                if (declaration is KtNamedFunction) {
                    appendParameters(declaration, "[", "]", skipLast = true)
                    declaration.valueParameters.last().nameAsName?.let {
                        appendFixedText(" = ")
                        appendName(it)
                    }
                }
            } else if (isInvoke) {
                appendFixedText("()")
                if (declaration is KtNamedFunction) {
                    appendParameters(declaration, "(", ")")
                }
            } else {
                appendFixedText("().")
                appendName(name)
                if (declaration is KtNamedFunction) {
                    appendParameters(declaration, "(", ")")
                }
            }
        }

        if (declaration is KtNamedFunction) {
            declaration.bodyExpression?.delete()
            declaration.equalsToken?.delete()

            if (isSetter || isInvoke) {
                val blockBody = ktPsiFactory.createSingleStatementBlock(body)
                declaration.add(blockBody)
            } else {
                declaration.add(ktPsiFactory.createEQ())
                declaration.add(body)
            }
        }
        else if (declaration is KtProperty) {
            declaration.setter?.delete()
            declaration.getter?.delete()
            val getter = ktPsiFactory.createPropertyGetter(body)
            declaration.add(getter)

            if (declaration.isVar) {
                val setterBody = ktPsiFactory.buildExpression {
                    appendName(Name.identifier("asDynamic"))
                    appendFixedText("().")
                    appendName(name)
                    appendFixedText(" = ")
                    appendName(Name.identifier("value"))
                }

                val setterStubProperty = ktPsiFactory.createProperty("val x: Unit set(value) { Unit }")
                val block = setterStubProperty.setter!!.bodyExpression as KtBlockExpression
                block.statements.single().replace(setterBody)
                declaration.add(setterStubProperty.setter!!)
            }
        }
    }

    private fun BuilderByPattern<KtExpression>.appendParameters(declaration: KtNamedFunction, lParenth: String, rParenth: String, skipLast: Boolean = false) {
        appendFixedText(lParenth)
        for ((index, param) in declaration.valueParameters.let { if (skipLast) it.take(it.size-1) else it }.withIndex()) {
            param.nameAsName?.let { paramName ->
                if (index > 0) {
                    appendFixedText(",")
                }
                appendName(paramName)
            }
        }
        appendFixedText(rParenth)
    }

    companion object : KotlinSingleIntentionActionFactory() {
        private fun KtAnnotationEntry.isJsAnnotation(vararg predefinedAnnotations: PredefinedAnnotation): Boolean {
            val bindingContext = analyze(BodyResolveMode.PARTIAL_WITH_DIAGNOSTICS)
            val annotationDescriptor = bindingContext[BindingContext.ANNOTATION, this]
            return annotationDescriptor != null && predefinedAnnotations.any { checkAnnotationName(annotationDescriptor, it.fqName) }
        }

        private fun KtAnnotationEntry.isJsNativeAnnotation(): Boolean {
            return isJsAnnotation(PredefinedAnnotation.NATIVE, PredefinedAnnotation.NATIVE_GETTER, PredefinedAnnotation.NATIVE_SETTER, PredefinedAnnotation.NATIVE_INVOKE )
        }

        private fun isExternalMemberDeclaration(psiElement: PsiElement): Boolean {
            return (psiElement is KtNamedFunction && psiElement.receiverTypeReference != null) ||
                   (psiElement is KtProperty && psiElement.receiverTypeReference != null)
        }

        private inline fun<reified T: PsiElement> getContainingElement(e: PsiElement): T? {
            var element: PsiElement? = e
            while (element != null) {
                if (element is T)
                    return element

                element = element.parent
            }
            return null
        }
        override fun createAction(diagnostic: Diagnostic): IntentionAction? {
            val e = diagnostic.psiElement
            when (diagnostic.factory) {
                ErrorsJs.WRONG_EXTERNAL_DECLARATION -> {
                    if (isExternalMemberDeclaration(e)) {
                        return MigrateExternalExtensionFix(e as KtNamedDeclaration)
                    }
                }
                Errors.DEPRECATION_ERROR, Errors.DEPRECATION -> {
                    if (getContainingElement<KtAnnotationEntry>(e)?.isJsNativeAnnotation() == true) {
                        getContainingElement<KtNamedDeclaration>(e)?.let {
                            return MigrateExternalExtensionFix(it)
                        }
                    }
                    if ((e as? KtNamedDeclaration)?.modifierList?.annotationEntries?.any { it.isJsNativeAnnotation() } == true) {
                        return MigrateExternalExtensionFix(e as KtNamedDeclaration)
                    }
                }
            }

            return null
        }
    }
}
