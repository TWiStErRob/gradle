/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.instrumentation.extensions.property;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;
import org.gradle.internal.instrumentation.extensions.property.PropertyUpgradeRequestExtra.UpgradedPropertyType;
import org.gradle.internal.instrumentation.model.CallInterceptionRequest;
import org.gradle.internal.instrumentation.model.CallableInfo;
import org.gradle.internal.instrumentation.model.ImplementationInfo;
import org.gradle.internal.instrumentation.processor.codegen.RequestGroupingInstrumentationClassSourceGenerator;

import javax.lang.model.element.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.gradle.internal.instrumentation.processor.codegen.TypeUtils.typeName;

public class PropertyUpgradeClassSourceGenerator extends RequestGroupingInstrumentationClassSourceGenerator {

    private static final String SELF_PARAMETER_NAME = "self";

    @Override
    protected String classNameForRequest(CallInterceptionRequest request) {
        return request.getRequestExtras().getByType(PropertyUpgradeRequestExtra.class)
            .map(PropertyUpgradeRequestExtra::getImplementationClassName)
            .orElse(null);
    }

    @Override
    protected Consumer<TypeSpec.Builder> classContentForClass(
        String className,
        Collection<CallInterceptionRequest> requestsClassGroup,
        Consumer<? super CallInterceptionRequest> onProcessedRequest,
        Consumer<? super GenerationResult.HasFailures.FailureInfo> onFailure
    ) {
        List<MethodSpec> methods = requestsClassGroup.stream()
            .map(request -> mapToMethodSpec(request, onProcessedRequest))
            .collect(Collectors.toList());
        return builder -> builder.addModifiers(Modifier.PUBLIC).addMethods(methods);
    }

    private static MethodSpec mapToMethodSpec(CallInterceptionRequest request, Consumer<? super CallInterceptionRequest> onProcessedRequest) {
        PropertyUpgradeRequestExtra implementationExtra = request.getRequestExtras()
            .getByType(PropertyUpgradeRequestExtra.class)
            .orElseThrow(() -> new RuntimeException(PropertyUpgradeRequestExtra.class.getSimpleName() + " should be present at this stage!"));

        CallableInfo callable = request.getInterceptedCallable();
        ImplementationInfo implementation = request.getImplementationInfo();
        List<ParameterSpec> parameters = callable.getParameters().stream()
            .map(parameter -> ParameterSpec.builder(typeName(parameter.getParameterType()), parameter.getName()).build())
            .collect(Collectors.toList());
        onProcessedRequest.accept(request);
        return MethodSpec.methodBuilder(implementation.getName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(typeName(callable.getOwner().getType()), SELF_PARAMETER_NAME)
            .addParameters(parameters)
            .addCode(generateMethodBody(implementation, implementationExtra))
            .returns(typeName(callable.getReturnType().getType()))
            .build();
    }

    private static CodeBlock generateMethodBody(ImplementationInfo implementation, PropertyUpgradeRequestExtra implementationExtra) {
        String propertyGetterName = implementationExtra.getInterceptedPropertyAccessorName();
        boolean isSetter = implementation.getName().startsWith("access_set_");
        UpgradedPropertyType upgradedPropertyType = implementationExtra.getUpgradedPropertyType();
        if (isSetter) {
            String setCall = getSetCall(upgradedPropertyType);
            if (implementationExtra.isFluentSetter()) {
                return CodeBlock.of("$N.$N()$N;\nreturn $N;", SELF_PARAMETER_NAME, propertyGetterName, setCall, SELF_PARAMETER_NAME);
            } else {
                return CodeBlock.of("$N.$N()$N;", SELF_PARAMETER_NAME, propertyGetterName, setCall);
            }
        } else {
            String getCall = getGetCall(upgradedPropertyType);
            return CodeBlock.of("return $N.$N()$N;", SELF_PARAMETER_NAME, propertyGetterName, getCall);
        }
    }

    private static String getGetCall(UpgradedPropertyType upgradedPropertyType) {
        switch (upgradedPropertyType) {
            case FILE_SYSTEM_LOCATION_PROPERTY:
                return ".getAsFile().get()";
            case CONFIGURABLE_FILE_COLLECTION:
                return "";
            default:
                return ".get()";
        }
    }

    private static String getSetCall(UpgradedPropertyType upgradedPropertyType) {
        switch (upgradedPropertyType) {
            case FILE_SYSTEM_LOCATION_PROPERTY:
                return ".fileValue(arg0)";
            case CONFIGURABLE_FILE_COLLECTION:
                return ".setFrom(arg0)";
            default:
                return ".set(arg0)";
        }
    }
}
