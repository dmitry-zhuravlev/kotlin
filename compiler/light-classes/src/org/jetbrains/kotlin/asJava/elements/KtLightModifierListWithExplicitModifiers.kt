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

package org.jetbrains.kotlin.asJava.elements

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationOwner
import com.intellij.psi.PsiModifierList
import com.intellij.psi.impl.light.LightModifierList
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.asJava.LightClassGenerationSupport
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.utils.indexOfFirst

abstract class KtLightModifierListWithExplicitModifiers(
        private val owner: KtLightElement<*, *>,
        modifiers: Array<String>
) : LightModifierList(owner.manager, KotlinLanguage.INSTANCE, *modifiers) {
    abstract val delegate: PsiAnnotationOwner

    private val _annotations by lazyPub { computeAnnotations(this, delegate) }

    override fun getParent() = owner

    override fun getAnnotations(): Array<out PsiAnnotation> = _annotations.value

    override fun getApplicableAnnotations() = delegate.applicableAnnotations

    override fun findAnnotation(@NonNls qualifiedName: String) = annotations.firstOrNull { it.qualifiedName == qualifiedName }

    override fun addAnnotation(@NonNls qualifiedName: String) = delegate.addAnnotation(qualifiedName)
}

internal fun computeAnnotations(lightElement: PsiModifierList,
                                delegate: PsiAnnotationOwner): CachedValue<Array<out PsiAnnotation>> {
    fun doCompute(): Array<PsiAnnotation> {
        val delegateAnnotations = delegate.annotations
        if (delegateAnnotations.isEmpty()) return emptyArray()

        val lightOwner = lightElement.parent as? KtLightElement<*, *>
        val declaration = lightOwner?.kotlinOrigin as? KtDeclaration
        if (declaration != null && !declaration.isValid) return PsiAnnotation.EMPTY_ARRAY
        val descriptor = declaration?.let { LightClassGenerationSupport.getInstance(lightElement.project).resolveToDescriptor(it) }
        val annotatedDescriptor = when {
            descriptor !is PropertyDescriptor || lightOwner !is KtLightMethod -> descriptor
            lightOwner.isGetter -> descriptor.getter
            lightOwner.isSetter -> descriptor.setter
            else -> descriptor
        }
        val ktAnnotations = annotatedDescriptor?.annotations?.getAllAnnotations() ?: emptyList()
        var nextIndex = 0
        val result = delegateAnnotations
                .map { clsAnnotation ->
                    val currentIndex = ktAnnotations.indexOfFirst(nextIndex) {
                        it.annotation.type.constructor.declarationDescriptor?.fqNameUnsafe?.asString() == clsAnnotation.qualifiedName
                    }
                    if (currentIndex >= 0) {
                        nextIndex = currentIndex + 1
                        val ktAnnotation = ktAnnotations[currentIndex]
                        val entry = ktAnnotation.annotation.source.getPsi() as? KtAnnotationEntry ?: return@map clsAnnotation
                        KtLightAnnotation(clsAnnotation, entry, lightElement)
                    }
                    else clsAnnotation
                }
                .toTypedArray()
        return result
    }

    return CachedValuesManager.getManager(lightElement.project).createCachedValue<Array<out PsiAnnotation>>(
            { CachedValueProvider.Result.create(doCompute(), PsiModificationTracker.OUT_OF_CODE_BLOCK_MODIFICATION_COUNT) },
            false
    )
}
