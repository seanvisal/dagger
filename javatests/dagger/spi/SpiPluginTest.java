/*
 * Copyright (C) 2018 The Dagger Authors.
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

package dagger.spi;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import dagger.internal.codegen.ComponentProcessor;
import javax.tools.JavaFileObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SpiPluginTest {
  @Test
  public void dependencyTraceAtBinding() {
    JavaFileObject foo =
        JavaFileObjects.forSourceLines(
            "test.Foo",
            "package test;",
            "",
            "import javax.inject.Inject;",
            "",
            "class Foo {",
            "  @Inject Foo() {}",
            "}");
    JavaFileObject component =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component",
            "interface TestComponent {",
            "  Foo foo();",
            "}");

    Compilation compilation =
        javac()
            .withProcessors(new ComponentProcessor())
            .withOptions("-Aerror_on_binding=test.Foo")
            .compile(component, foo);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining(
            lines(
                "[FailingPlugin] Bad Binding!",
                "      test.Foo is provided at",
                "          test.TestComponent.foo()"))
        .inFile(component)
        .onLineContaining("Foo foo();");
  }

  @Test
  public void dependencyTraceAtDependencyRequest() {
    JavaFileObject foo =
        JavaFileObjects.forSourceLines(
            "test.Foo",
            "package test;",
            "",
            "import javax.inject.Inject;",
            "",
            "class Foo {",
            "  @Inject Foo(Duplicated inFooDep) {}",
            "}");
    JavaFileObject duplicated =
        JavaFileObjects.forSourceLines(
            "test.Duplicated",
            "package test;",
            "",
            "import javax.inject.Inject;",
            "",
            "class Duplicated {",
            "  @Inject Duplicated() {}",
            "}");
    JavaFileObject entryPoint =
        JavaFileObjects.forSourceLines(
            "test.EntryPoint",
            "package test;",
            "",
            "import javax.inject.Inject;",
            "",
            "class EntryPoint {",
            "  @Inject EntryPoint(Foo foo, Duplicated dup1, Duplicated dup2) {}",
            "}");
    JavaFileObject chain1 =
        JavaFileObjects.forSourceLines(
            "test.Chain1",
            "package test;",
            "",
            "import javax.inject.Inject;",
            "",
            "class Chain1 {",
            "  @Inject Chain1(Chain2 chain) {}",
            "}");
    JavaFileObject chain2 =
        JavaFileObjects.forSourceLines(
            "test.Chain2",
            "package test;",
            "",
            "import javax.inject.Inject;",
            "",
            "class Chain2 {",
            "  @Inject Chain2(Chain3 chain) {}",
            "}");
    JavaFileObject chain3 =
        JavaFileObjects.forSourceLines(
            "test.Chain3",
            "package test;",
            "",
            "import javax.inject.Inject;",
            "",
            "class Chain3 {",
            "  @Inject Chain3(Foo foo) {}",
            "}");
    JavaFileObject component =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component",
            "interface TestComponent {",
            "  EntryPoint entryPoint();",
            "  Chain1 chain();",
            "}");

    CompilationFactory compilationFactory =
        new CompilationFactory(component, foo, duplicated, entryPoint, chain1, chain2, chain3);

    assertThat(compilationFactory.compilationWithErrorOnDependency("entryPoint"))
        .hadErrorContaining(
            lines(
                "[FailingPlugin] Bad Dependency!",
                "      test.EntryPoint is provided at",
                "          test.TestComponent.entryPoint()"))
        .inFile(component)
        .onLineContaining("EntryPoint entryPoint();");
    assertThat(compilationFactory.compilationWithErrorOnDependency("dup1"))
        .hadErrorContaining(
            lines(
                "[FailingPlugin] Bad Dependency!",
                "      test.Duplicated is injected at",
                "          test.EntryPoint.<init>(…, dup1, …)",
                "      test.EntryPoint is provided at",
                "          test.TestComponent.entryPoint()"))
        .inFile(component)
        .onLineContaining("EntryPoint entryPoint();");
    assertThat(compilationFactory.compilationWithErrorOnDependency("dup2"))
        .hadErrorContaining(
            lines(
                "[FailingPlugin] Bad Dependency!",
                "      test.Duplicated is injected at",
                "          test.EntryPoint.<init>(…, dup2)",
                "      test.EntryPoint is provided at",
                "          test.TestComponent.entryPoint()"))
        .inFile(component)
        .onLineContaining("EntryPoint entryPoint();");
    // Note that this compilation results in one error being reported twice since there are
    // two entry points that depend on test.Foo
    Compilation inFooDepCompilation =
        compilationFactory.compilationWithErrorOnDependency("inFooDep");
    assertThat(inFooDepCompilation)
        .hadErrorContaining(
            lines(
                "[FailingPlugin] Bad Dependency!",
                "      test.Duplicated is injected at",
                "          test.Foo.<init>(inFooDep)",
                "      test.Foo is injected at",
                "          test.EntryPoint.<init>(foo, …)",
                "      test.EntryPoint is provided at",
                "          test.TestComponent.entryPoint()"))
        .inFile(component)
        .onLineContaining("EntryPoint entryPoint();");
    assertThat(inFooDepCompilation)
        .hadErrorContaining(
            lines(
                "[FailingPlugin] Bad Dependency!",
                "      test.Duplicated is injected at",
                "          test.Foo.<init>(inFooDep)",
                "      test.Foo is injected at",
                "          test.Chain3.<init>(foo)",
                "      test.Chain3 is injected at",
                "          test.Chain2.<init>(chain)",
                "      test.Chain2 is injected at",
                "          test.Chain1.<init>(chain)",
                "      test.Chain1 is provided at",
                "          test.TestComponent.chain()"))
        .inFile(component)
        .onLineContaining("Chain1 chain();");
  }

  @Test
  public void dependencyTraceAtDependencyRequest_subcomponents() {
    JavaFileObject foo =
        JavaFileObjects.forSourceLines(
            "test.Foo",
            "package test;",
            "",
            "import javax.inject.Inject;",
            "",
            "class Foo {",
            "  @Inject Foo() {}",
            "}");
    JavaFileObject entryPoint =
        JavaFileObjects.forSourceLines(
            "test.EntryPoint",
            "package test;",
            "",
            "import javax.inject.Inject;",
            "",
            "class EntryPoint {",
            "  @Inject EntryPoint(Foo foo) {}",
            "}");
    JavaFileObject component =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component",
            "interface TestComponent {",
            "  TestSubcomponent sub();",
            "}");
    JavaFileObject subcomponent =
        JavaFileObjects.forSourceLines(
            "test.TestSubcomponent",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "",
            "@Subcomponent",
            "interface TestSubcomponent {",
            "  EntryPoint childEntryPoint();",
            "}");

    CompilationFactory compilationFactory =
        new CompilationFactory(component, subcomponent, foo, entryPoint);
    assertThat(compilationFactory.compilationWithErrorOnDependency("childEntryPoint"))
        .hadErrorContaining(
            lines(
                "[FailingPlugin] Bad Dependency!",
                "      test.EntryPoint is provided at",
                "          test.TestSubcomponent.childEntryPoint()",
                "  component path: test.TestComponent → test.TestSubcomponent"))
        .inFile(component)
        .onLineContaining("interface TestComponent");
    assertThat(compilationFactory.compilationWithErrorOnDependency("foo"))
        .hadErrorContaining(
            lines(
                "[FailingPlugin] Bad Dependency!",
                "      test.Foo is injected at",
                "          test.EntryPoint.<init>(foo)",
                "      test.EntryPoint is provided at",
                "          test.TestSubcomponent.childEntryPoint()",
                // TODO(ronshapiro): Maybe make the component path resemble a stack trace:
                //     test.TestSubcomponent is a child of
                //         test.TestComponent
                "  component path: test.TestComponent → test.TestSubcomponent"))
        .inFile(component)
        .onLineContaining("interface TestComponent");
  }

  @Test
  public void errorOnComponent() {
    JavaFileObject component =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component",
            "interface TestComponent {}");

    Compilation compilation =
        javac()
            .withProcessors(new ComponentProcessor())
            .withOptions("-Aerror_on_component")
            .compile(component);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining("[FailingPlugin] Bad Component!")
        .inFile(component)
        .onLineContaining("interface TestComponent");
  }

  @Test
  public void errorOnSubcomponent() {
    JavaFileObject subcomponent =
        JavaFileObjects.forSourceLines(
            "test.TestSubcomponent",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "",
            "@Subcomponent",
            "interface TestSubcomponent {}");
    JavaFileObject component =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component",
            "interface TestComponent {",
            "  TestSubcomponent subcomponent();",
            "}");

    Compilation compilation =
        javac()
            .withProcessors(new ComponentProcessor())
            .withOptions("-Aerror_on_subcomponents")
            .compile(component, subcomponent);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining(
            lines(
                "[FailingPlugin] Bad Subcomponent!",
                "  component path: test.TestComponent → test.TestSubcomponent"))
        .inFile(component)
        .onLineContaining("interface TestComponent");
  }

  // SpiDiagnosticReporter uses a shortest path algorithm to determine a dependency trace to a
  // binding. Without modifications, this would produce a strange error if a shorter path exists
  // from one entrypoint, through a @Module.subcomponents builder binding edge, and to the binding
  // usage within the subcomponent. Therefore, when scanning for the shortest path, we only consider
  // BindingNodes so we don't cross component boundaries. This test exhibits this case.
  @Test
  public void shortestPathToBindingExistsThroughSubcomponentBuilder() {
    JavaFileObject chain1 =
        JavaFileObjects.forSourceLines(
            "test.Chain1",
            "package test;",
            "",
            "import javax.inject.Inject;",
            "",
            "class Chain1 {",
            "  @Inject Chain1(Chain2 chain) {}",
            "}");
    JavaFileObject chain2 =
        JavaFileObjects.forSourceLines(
            "test.Chain2",
            "package test;",
            "",
            "import javax.inject.Inject;",
            "",
            "class Chain2 {",
            "  @Inject Chain2(Chain3 chain) {}",
            "}");
    JavaFileObject chain3 =
        JavaFileObjects.forSourceLines(
            "test.Chain3",
            "package test;",
            "",
            "import javax.inject.Inject;",
            "",
            "class Chain3 {",
            "  @Inject Chain3(ExposedOnSubcomponent exposedOnSubcomponent) {}",
            "}");
    JavaFileObject exposedOnSubcomponent =
        JavaFileObjects.forSourceLines(
            "test.ExposedOnSubcomponent",
            "package test;",
            "",
            "import javax.inject.Inject;",
            "",
            "class ExposedOnSubcomponent {",
            "  @Inject ExposedOnSubcomponent() {}",
            "}");
    JavaFileObject subcomponent =
        JavaFileObjects.forSourceLines(
            "test.TestSubcomponent",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "",
            "@Subcomponent",
            "interface TestSubcomponent {",
            "  ExposedOnSubcomponent exposedOnSubcomponent();",
            "",
            "  @Subcomponent.Builder",
            "  interface Builder {",
            "    TestSubcomponent build();",
            "  }",
            "}");
    JavaFileObject subcomponentModule =
        JavaFileObjects.forSourceLines(
            "test.SubcomponentModule",
            "package test;",
            "",
            "import dagger.Module;",
            "",
            "@Module(subcomponents = TestSubcomponent.class)",
            "interface SubcomponentModule {}");
    JavaFileObject component =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "import javax.inject.Singleton;",
            "",
            "@Singleton",
            "@Component(modules = SubcomponentModule.class)",
            "interface TestComponent {",
            "  Chain1 chain();",
            "  TestSubcomponent.Builder subcomponent();",
            "}");

    Compilation compilation =
        javac()
            .withProcessors(new ComponentProcessor())
            .withOptions("-Aerror_on_binding=test.ExposedOnSubcomponent")
            .compile(
                component,
                subcomponent,
                chain1,
                chain2,
                chain3,
                exposedOnSubcomponent,
                subcomponentModule);
    assertThat(compilation)
        .hadErrorContaining(
            lines(
                "[FailingPlugin] Bad Binding!",
                "      test.ExposedOnSubcomponent is injected at",
                "          test.Chain3.<init>(exposedOnSubcomponent)",
                "      test.Chain3 is injected at",
                "          test.Chain2.<init>(chain)",
                "      test.Chain2 is injected at",
                "          test.Chain1.<init>(chain)",
                "      test.Chain1 is provided at",
                "          test.TestComponent.chain()"))
        .inFile(component)
        .onLineContaining("Chain1 chain();");
    assertThat(compilation)
        .hadErrorContaining(
            lines(
                // TODO(ronshapiro): should this error be reported if it's already been reported at
                // an ancestor component?
                "[FailingPlugin] Bad Binding!",
                "      test.ExposedOnSubcomponent is provided at",
                "          test.TestSubcomponent.exposedOnSubcomponent()",
                "  component path: test.TestComponent → test.TestSubcomponent"))
        .inFile(component)
        .onLineContaining("interface TestComponent");
  }

  // This works around an issue in the opensource compile testing where only one diagnostic is
  // recorded per line. When multiple validation items resolve to the same entry point, we can
  // only see the first. This helper class makes it easier to compile all of the files in the test
  // multiple times with different options to single out each error
  private static class CompilationFactory {
    private final ImmutableList<JavaFileObject> javaFileObjects;

    CompilationFactory(JavaFileObject... javaFileObjects) {
      this.javaFileObjects = ImmutableList.copyOf(javaFileObjects);
    }

    private Compilation compilationWithErrorOnDependency(String dependencySimpleName) {
      return javac()
          .withProcessors(new ComponentProcessor())
          .withOptions("-Aerror_on_dependency=" + dependencySimpleName)
          .compile(javaFileObjects);
    }
  }

  private static String lines(String... lines) {
    return Joiner.on('\n').join(lines);
  }
}
