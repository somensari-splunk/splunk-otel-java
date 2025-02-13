/*
 * Copyright Splunk Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.splunk.opentelemetry.instrumentation.liberty;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import com.ibm.ws.kernel.productinfo.ProductInfo;
import com.splunk.opentelemetry.javaagent.bootstrap.WebengineHolder;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class LibertyAttributesInstrumentationModule extends InstrumentationModule {

  public LibertyAttributesInstrumentationModule() {
    super("liberty");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("com.ibm.ws.kernel.boot.internal.KernelBootstrap");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return Collections.singletonList(new Instrumentation());
  }

  public static class Instrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named("com.ibm.ws.kernel.boot.internal.KernelBootstrap");
    }

    @Override
    public void transform(TypeTransformer typeTransformer) {
      typeTransformer.applyAdviceToMethod(
          isMethod().and(isPublic()).and(named("go")),
          LibertyAttributesInstrumentationModule.class.getName() + "$WebengineInitializedAdvice");
    }
  }

  @SuppressWarnings("unused")
  public static class WebengineInitializedAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      String version = null;
      for (ProductInfo p : ProductInfo.getAllProductInfo().values()) {
        // older versions have only WebSphereApplicationServer.properties
        // openliberty distribution only has openliberty.properties
        // ibm distribution has both
        if ("openliberty.properties".equals(p.getFile().getName())
            || "WebSphereApplicationServer.properties".equals(p.getFile().getName())) {
          version = p.getVersion();
          break;
        }
      }

      WebengineHolder.trySetVersion(version);
      WebengineHolder.trySetName("websphere liberty");
    }
  }
}
