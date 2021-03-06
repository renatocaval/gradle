/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.api.internal.artifacts.transform;

import com.google.common.collect.Lists;
import org.gradle.api.Action;
import org.gradle.api.ActionConfiguration;
import org.gradle.api.NonExtensible;
import org.gradle.api.artifacts.transform.ArtifactTransform;
import org.gradle.api.artifacts.transform.ArtifactTransformSpec;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.VariantTransform;
import org.gradle.api.artifacts.transform.VariantTransformConfigurationException;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.DefaultActionConfiguration;
import org.gradle.api.internal.artifacts.VariantTransformRegistry;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.tasks.properties.PropertyWalker;
import org.gradle.internal.classloader.ClassLoaderHierarchyHasher;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.isolation.IsolatableFactory;
import org.gradle.internal.snapshot.ValueSnapshotter;
import org.gradle.internal.service.ServiceRegistry;

import javax.annotation.Nullable;
import java.util.List;

public class DefaultVariantTransformRegistry implements VariantTransformRegistry {
    private static final Object[] NO_PARAMETERS = new Object[0];
    private final List<Registration> transforms = Lists.newArrayList();
    private final ImmutableAttributesFactory immutableAttributesFactory;
    private final IsolatableFactory isolatableFactory;
    private final ClassLoaderHierarchyHasher classLoaderHierarchyHasher;
    private final ServiceRegistry services;
    private final InstantiatorFactory instantiatorFactory;
    private final TransformerInvoker transformerInvoker;
    private final ValueSnapshotter valueSnapshotter;
    private final PropertyWalker propertyWalker;

    public DefaultVariantTransformRegistry(InstantiatorFactory instantiatorFactory, ImmutableAttributesFactory immutableAttributesFactory, IsolatableFactory isolatableFactory, ClassLoaderHierarchyHasher classLoaderHierarchyHasher, ServiceRegistry services, TransformerInvoker transformerInvoker, ValueSnapshotter valueSnapshotter, PropertyWalker propertyWalker) {
        this.instantiatorFactory = instantiatorFactory;
        this.immutableAttributesFactory = immutableAttributesFactory;
        this.isolatableFactory = isolatableFactory;
        this.classLoaderHierarchyHasher = classLoaderHierarchyHasher;
        this.services = services;
        this.transformerInvoker = transformerInvoker;
        this.valueSnapshotter = valueSnapshotter;
        this.propertyWalker = propertyWalker;
    }

    @Override
    public void registerTransform(Action<? super VariantTransform> registrationAction) {
        UntypedRegistration registration = instantiatorFactory.decorateLenient().newInstance(UntypedRegistration.class, immutableAttributesFactory, instantiatorFactory);
        register(registration, registrationAction);
    }

    @Override
    public <T> void registerTransform(Class<T> configurationType, Action<? super ArtifactTransformSpec<T>> registrationAction) {
        // TODO - should decorate
        T configuration = instantiatorFactory.inject(services).newInstance(configurationType);
        TypedRegistration<T> registration = instantiatorFactory.decorateLenient().newInstance(TypedRegistration.class, configuration, immutableAttributesFactory);
        register(registration, registrationAction);
    }

    private <T extends RecordingRegistration> void register(T registration, Action<? super T> registrationAction) {
        registrationAction.execute(registration);

        if (registration.actionType == null) {
            throw new VariantTransformConfigurationException("Could not register transform: an ArtifactTransform must be provided.");
        }
        if (registration.to.isEmpty()) {
            throw new VariantTransformConfigurationException("Could not register transform: at least one 'to' attribute must be provided.");
        }
        if (registration.from.isEmpty()) {
            throw new VariantTransformConfigurationException("Could not register transform: at least one 'from' attribute must be provided.");
        }
        if (!registration.from.keySet().containsAll(registration.to.keySet())) {
            throw new VariantTransformConfigurationException("Could not register transform: each 'to' attribute must be included as a 'from' attribute.");
        }

        // TODO - should calculate this lazily
        Object[] parameters = registration.getTransformParameters();
        Object config = registration.getParameterObject();

        Registration finalizedRegistration = DefaultTransformationRegistration.create(registration.from.asImmutable(), registration.to.asImmutable(), registration.actionType, config, parameters, isolatableFactory, classLoaderHierarchyHasher, instantiatorFactory, transformerInvoker, valueSnapshotter, propertyWalker);
        transforms.add(finalizedRegistration);
    }

    public Iterable<Registration> getTransforms() {
        return transforms;
    }

    public static abstract class RecordingRegistration {
        final AttributeContainerInternal from;
        final AttributeContainerInternal to;
        Class<? extends ArtifactTransform> actionType;
        Action<? super ActionConfiguration> configAction;

        public RecordingRegistration(ImmutableAttributesFactory immutableAttributesFactory) {
            from = immutableAttributesFactory.mutable();
            to = immutableAttributesFactory.mutable();
        }

        public AttributeContainer getFrom() {
            return from;
        }

        public AttributeContainer getTo() {
            return to;
        }

        abstract Object[] getTransformParameters();

        @Nullable
        abstract Object getParameterObject();
    }

    @NonExtensible
    public static class UntypedRegistration extends RecordingRegistration implements VariantTransform {
        private final InstantiatorFactory instantiatorFactory;

        public UntypedRegistration(ImmutableAttributesFactory immutableAttributesFactory, InstantiatorFactory instantiatorFactory) {
            super(immutableAttributesFactory);
            this.instantiatorFactory = instantiatorFactory;
        }

        @Override
        public void artifactTransform(Class<? extends ArtifactTransform> type) {
            artifactTransform(type, null);
        }

        @Override
        public void artifactTransform(Class<? extends ArtifactTransform> type, @Nullable Action<? super ActionConfiguration> config) {
            if (this.actionType != null) {
                throw new VariantTransformConfigurationException("Could not register transform: only one ArtifactTransform may be provided for registration.");
            }
            this.actionType = type;
            this.configAction = config;
        }

        @Override
        Object[] getTransformParameters() {
            if (configAction == null) {
                return NO_PARAMETERS;
            }
            ActionConfiguration config = instantiatorFactory.decorateLenient().newInstance(DefaultActionConfiguration.class);
            configAction.execute(config);
            return config.getParams();
        }

        @Override
        Object getParameterObject() {
            return null;
        }
    }

    @NonExtensible
    public static class TypedRegistration<T> extends RecordingRegistration implements ArtifactTransformSpec<T> {
        private final T parameterObject;

        public TypedRegistration(T parameterObject, ImmutableAttributesFactory immutableAttributesFactory) {
            super(immutableAttributesFactory);
            this.parameterObject = parameterObject;
            TransformAction transformAction = parameterObject.getClass().getAnnotation(TransformAction.class);
            if (transformAction != null) {
                actionType = transformAction.value();
            }
        }

        @Override
        public Class<? extends ArtifactTransform> getActionClass() {
            return actionType;
        }

        @Override
        public void setActionClass(Class<? extends ArtifactTransform> implementationClass) {
            this.actionType = implementationClass;
        }

        @Override
        public T getParameters() {
            return parameterObject;
        }

        @Override
        public void parameters(Action<? super T> action) {
            action.execute(parameterObject);
        }

        @Nullable
        @Override
        Object getParameterObject() {
            return parameterObject;
        }

        @Override
        Object[] getTransformParameters() {
            return new Object[0];
        }
    }
}
