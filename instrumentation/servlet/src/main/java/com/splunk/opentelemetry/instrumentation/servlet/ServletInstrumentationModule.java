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

package com.splunk.opentelemetry.instrumentation.servlet;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.splunk.opentelemetry.instrumentation.servertiming.ServerTimingHeader;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.api.Java8BytecodeBridge;
import java.util.Collections;
import java.util.List;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class ServletInstrumentationModule extends InstrumentationModule {
  public ServletInstrumentationModule() {
    super("servlet");
  }

  // run after the upstream servlet instrumentation - server span needs to be accessible here
  @Override
  public int order() {
    return 1;
  }

  // enable the instrumentation only if the server-timing header flag is on
  @Override
  protected boolean defaultEnabled() {
    return super.defaultEnabled() && ServerTimingHeader.shouldEmitServerTimingHeader();
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("javax.servlet.Filter", "javax.servlet.http.HttpServlet");
  }

  @Override
  public boolean isHelperClass(String className) {
    return className.startsWith("com.splunk.opentelemetry.instrumentation");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return Collections.singletonList(new ServletAndFilterInstrumentation());
  }

  private static final class ServletAndFilterInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return hasSuperType(namedOneOf("javax.servlet.Filter", "javax.servlet.http.HttpServlet"));
    }

    @Override
    public void transform(TypeTransformer typeTransformer) {
      typeTransformer.applyAdviceToMethod(
          namedOneOf("doFilter", "service")
              .and(takesArgument(0, named("javax.servlet.ServletRequest")))
              .and(takesArgument(1, named("javax.servlet.ServletResponse")))
              .and(isPublic()),
          getClass().getName() + "$AddHeadersAdvice");
    }

    public static class AddHeadersAdvice {
      @Advice.OnMethodEnter(suppress = Throwable.class)
      public static void onEnter(@Advice.Argument(1) ServletResponse response) {
        if (response instanceof HttpServletResponse) {
          HttpServletResponse httpResponse = (HttpServletResponse) response;
          Context context = Java8BytecodeBridge.currentContext();
          ServerTimingHeader.setHeaders(context, httpResponse, HeadersSetter.INSTANCE);
        }
      }
    }
  }

  public static final class HeadersSetter implements TextMapSetter<HttpServletResponse> {
    public static final HeadersSetter INSTANCE = new HeadersSetter();

    @Override
    public void set(HttpServletResponse carrier, String key, String value) {
      carrier.setHeader(key, value);
    }
  }
}
