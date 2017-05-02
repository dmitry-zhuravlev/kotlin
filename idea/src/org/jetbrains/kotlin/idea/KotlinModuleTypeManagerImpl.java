/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea;

import com.intellij.facet.Facet;
import com.intellij.facet.FacetManager;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.module.Module;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.idea.configuration.KotlinModuleTypeManager;
import org.jetbrains.kotlin.idea.framework.KotlinLibraryUtilKt;

public class KotlinModuleTypeManagerImpl extends KotlinModuleTypeManager {
    @Override
    public boolean isAndroidGradleModule(@NotNull Module module) {
        return hasAndroidFacet(module) && isGradleModule(module);
    }

    private static boolean hasAndroidFacet(@NotNull Module module) {
        for (Facet facet : FacetManager.getInstance(module).getAllFacets()) {
            if (facet.getName().equals("Android")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isGradleModule(@NotNull Module module) {
        return ExternalSystemApiUtil.isExternalSystemAwareModule(KotlinLibraryUtilKt.getGRADLE_SYSTEM_ID(), module);
    }

    @Override
    public boolean isKobaltModule(@NotNull Module module) {
        return ExternalSystemApiUtil.isExternalSystemAwareModule(KotlinLibraryUtilKt.getKOBALT_SYSTEM_ID(), module);
    }
}
