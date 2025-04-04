/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.metadata;

import static org.mule.metadata.java.api.utils.JavaTypeUtils.getType;
import static org.mule.runtime.api.metadata.MetadataKeyBuilder.newKey;
import static org.mule.runtime.api.metadata.resolving.FailureCode.INVALID_METADATA_KEY;
import static org.mule.runtime.api.util.Preconditions.checkArgument;
import static org.mule.runtime.extension.api.dsql.DsqlParser.isDsqlQuery;
import static org.mule.runtime.module.extension.internal.util.IntrospectionUtils.getFieldValue;

import static java.lang.String.format;
import static java.lang.String.valueOf;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import org.mule.metadata.api.model.BooleanType;
import org.mule.metadata.api.model.MetadataType;
import org.mule.metadata.api.model.ObjectType;
import org.mule.metadata.api.model.StringType;
import org.mule.metadata.api.visitor.MetadataTypeVisitor;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.meta.model.parameter.ParameterModel;
import org.mule.runtime.api.metadata.MetadataKey;
import org.mule.runtime.api.metadata.MetadataKeyBuilder;
import org.mule.runtime.api.metadata.MetadataResolvingException;
import org.mule.runtime.api.metadata.resolving.FailureCode;
import org.mule.runtime.api.util.LazyValue;
import org.mule.runtime.api.util.Reference;
import org.mule.runtime.extension.api.annotation.metadata.MetadataKeyId;
import org.mule.runtime.extension.api.dsql.DsqlParser;
import org.mule.runtime.extension.api.dsql.DsqlQuery;
import org.mule.runtime.extension.api.metadata.NullMetadataKey;
import org.mule.runtime.extension.api.property.MetadataKeyIdModelProperty;
import org.mule.runtime.extension.api.property.MetadataKeyPartModelProperty;
import org.mule.runtime.module.extension.internal.loader.java.property.DeclaringMemberModelProperty;
import org.mule.runtime.module.extension.internal.loader.java.property.QueryParameterModelProperty;
import org.mule.runtime.module.extension.internal.runtime.objectbuilder.DefaultObjectBuilder;
import org.mule.runtime.module.extension.internal.runtime.resolver.StaticValueResolver;
import org.mule.runtime.module.extension.internal.util.ReflectionCache;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Provides an instance of the annotated {@link MetadataKeyId} parameter type. The instance will be populated with all the
 * corresponding values of the passed {@link Map} key.
 *
 * @since 4.0
 */
final class MetadataKeyIdObjectResolver {

  private static final Supplier<DsqlParser> dsqlParser = new LazyValue<>(DsqlParser::getInstance);
  private final ComponentModel component;
  private final List<ParameterModel> keyParts;
  private final Map<String, ParameterModel> parameterModelIndex = new HashMap<>();
  private final ReflectionCache reflectionCache = new ReflectionCache();

  public MetadataKeyIdObjectResolver(ComponentModel component) {
    checkArgument(component != null, "The ComponentModel cannot be null");
    this.component = component;
    this.keyParts = getMetadataKeyPartsAndPopulateIndex(component);
  }

  /**
   * Given a partial {@link MetadataKey}, return the populated key in the Type that the component parameter requires.
   *
   * @param key the {@link MetadataKey} associated to the {@link MetadataKeyId}
   * @return a new instance of the {@link MetadataKeyId} parameter {@code type} with the values of the passed {@link MetadataKey}
   * @throws MetadataResolvingException if parameter types is not instantiable.
   */
  public Object resolveWithPartialKey(MetadataKey key) throws MetadataResolvingException {
    return doResolve(key, true);
  }

  /**
   * Given {@link MetadataKey}, return the populated key in the Type that the component parameter requires.
   *
   * @param key the {@link MetadataKey} associated to the {@link MetadataKeyId}
   * @return a new instance of the {@link MetadataKeyId} parameter {@code type} with the values of the passed {@link MetadataKey}
   * @throws MetadataResolvingException if:
   *                                    <ul>
   *                                    <li>Parameter types is not instantiable</li>
   *                                    <li>{@param key} does not provide the required levels</li>
   *                                    </ul>
   */
  public Object resolve(MetadataKey key) throws MetadataResolvingException {
    return doResolve(key, false);
  }

  private Object doResolve(MetadataKey key, boolean partial) throws MetadataResolvingException {
    if (isKeyLess()) {
      return NullMetadataKey.ID;
    }
    final MetadataKeyIdModelProperty keyIdModelProperty = findMetadataKeyIdModelProperty(component);
    MetadataType type = keyIdModelProperty.getType();
    KeyMetadataTypeVisitor visitor = new KeyMetadataTypeVisitor(key.getId(), getType(type)) {

      @Override
      protected Map<Field, String> getFieldValuesMap() throws MetadataResolvingException {
        return keyToFieldValueMap(key, partial);
      }
    };
    type.accept(visitor);
    return visitor.getResultId();
  }

  /**
   * Returns the populated key in the Type that the component parameter requires by looking for default values, if no
   * {@link MetadataKeyId} is present an empty value is returned since is a key less component.
   * <p>
   * If a key should be built and there is at least one default value missing an {@link IllegalArgumentException} is thrown.
   *
   * @return a new instance of the {@link MetadataKeyId} parameter {@code type}.
   * @throws MetadataResolvingException if the Parameter type is not instantiable.
   * @throws IllegalArgumentException   if cannot found the required default values for an specified key.
   */
  public Object resolve() throws MetadataResolvingException {

    if (isKeyLess()) {
      return NullMetadataKey.ID;
    }

    if (!keyParts.stream().allMatch(p -> p.getDefaultValue() != null)) {
      throw new IllegalArgumentException("Could not build metadata key from an object that does"
          + " not have a default value for all it's components.");
    }

    String id = keyParts.get(0).getDefaultValue().toString();
    final MetadataKeyIdModelProperty keyIdModelProperty = findMetadataKeyIdModelProperty(component);
    MetadataType type = keyIdModelProperty.getType();
    KeyMetadataTypeVisitor visitor = new KeyMetadataTypeVisitor(id, getType(type)) {

      @Override
      protected Map<Field, String> getFieldValuesMap() {
        return keyParts.stream()
            .filter(p -> p.getModelProperty(DeclaringMemberModelProperty.class).isPresent())
            .collect(toMap(p -> p.getModelProperty(DeclaringMemberModelProperty.class).get().getDeclaringField(),
                           p -> p.getDefaultValue().toString()));
      }
    };
    type.accept(visitor);
    return visitor.getResultId();
  }

  private MetadataKeyBuilder getKeyFromField(Object resolvedKey, DeclaringMemberModelProperty declaringMemberModelProperty,
                                             ReflectionCache reflectionCache)
      throws Exception {
    return newKey(valueOf(getFieldValue(resolvedKey, declaringMemberModelProperty.getDeclaringField().getName(),
                                        reflectionCache)));
  }

  /**
   * Given a {@link Object} representing the resolved value for a {@link MetadataKey}, generates the {@link MetadataKey} object.
   *
   * @param resolvedKey
   * @return {@link MetadataKey} reconstructed from the resolved object key
   * @throws MetadataResolvingException
   */
  MetadataKey reconstructKeyFromType(Object resolvedKey, ReflectionCache reflectionCache) throws MetadataResolvingException {
    if (isKeyLess() || resolvedKey == null) {
      return new NullMetadataKey();
    }

    if (keyParts.size() == 1) {
      return newKey(valueOf(resolvedKey)).build();
    }

    MetadataKeyBuilder rootBuilder = null;
    MetadataKeyBuilder childBuilder = null;


    for (ParameterModel p : keyParts) {
      try {
        if (p.getModelProperty(DeclaringMemberModelProperty.class).isPresent()) {
          MetadataKeyBuilder fieldBuilder =
              getKeyFromField(resolvedKey, p.getModelProperty(DeclaringMemberModelProperty.class).get(), reflectionCache);
          if (rootBuilder == null) {
            rootBuilder = fieldBuilder;
            childBuilder = rootBuilder;
          } else {
            childBuilder.withChild(fieldBuilder);
            childBuilder = fieldBuilder;
          }
        }
      } catch (Exception e) {
        throw new MetadataResolvingException("Could not construct Metadata Key part for parameter " + p.getName(),
                                             FailureCode.INVALID_METADATA_KEY, e);
      }
    }

    return rootBuilder != null ? rootBuilder.build() : new NullMetadataKey();
  }

  private MetadataKeyIdModelProperty findMetadataKeyIdModelProperty(ComponentModel component)
      throws MetadataResolvingException {
    return component.getModelProperty(MetadataKeyIdModelProperty.class)
        .orElseThrow(() -> buildException(format("Component '%s' doesn't have a MetadataKeyId "
            + "parameter associated", component.getName())));
  }

  private Object instantiateFromFieldValue(Class<?> metadataKeyType, Map<Field, String> fieldValueMap)
      throws MetadataResolvingException {
    try {
      DefaultObjectBuilder objectBuilder = new DefaultObjectBuilder<>(metadataKeyType, reflectionCache);
      fieldValueMap.forEach((f, v) -> objectBuilder.addPropertyResolver(f, new StaticValueResolver<>(v)));
      return objectBuilder.build(null);
    } catch (Exception e) {
      throw buildException(format("MetadataKey object of type '%s' from the component '%s' could not be instantiated",
                                  metadataKeyType.getSimpleName(), component.getName()),
                           e);
    }
  }

  private Map<Field, String> keyToFieldValueMap(MetadataKey key, boolean partial) throws MetadataResolvingException {
    final Map<String, Field> fieldParts = keyParts.stream()
        .filter(p -> p.getModelProperty(DeclaringMemberModelProperty.class).isPresent())
        .map(p -> p.getModelProperty(DeclaringMemberModelProperty.class).get().getDeclaringField())
        .collect(toMap(Field::getName, identity()));

    final Map<String, String> currentParts = getCurrentParts(key);
    if (!partial) {
      final List<String> missingParts = fieldParts.keySet()
          .stream()
          // If the key is not in index, return true. Else, check if required.
          .filter(partName -> !parameterModelIndex.containsKey(partName) || parameterModelIndex.get(partName).isRequired())
          .filter(partName -> !currentParts.containsKey(partName))
          .collect(toList());

      if (!missingParts.isEmpty()) {
        throw buildException(format("The given MetadataKey does not provide all the required levels. Missing levels: %s",
                                    missingParts));
      }
    }

    return currentParts.entrySet().stream().filter(keyEntry -> fieldParts.containsKey(keyEntry.getKey()))
        .collect(toMap(keyEntry -> fieldParts.get(keyEntry.getKey()), Map.Entry::getValue));
  }

  private Map<String, String> getCurrentParts(MetadataKey key) throws MetadataResolvingException {
    Map<String, String> keyParts = new HashMap<>();
    keyParts.put(key.getPartName(), key.getId());

    while (!key.getChilds().isEmpty()) {
      if (key.getChilds().size() > 1) {
        final List<String> keyNames = key.getChilds().stream().map(MetadataKey::getId).collect(toList());
        throw buildException(format("MetadataKey used for Metadata resolution must only have one child per level. "
            + "Key '%s' has %s as children.", key.getId(), keyNames));
      }
      key = key.getChilds().iterator().next();
      keyParts.put(key.getPartName(), key.getId());
    }
    return keyParts;
  }

  private MetadataResolvingException buildException(String message) {
    return buildException(message, null);
  }

  private MetadataResolvingException buildException(String message, Exception cause) {
    return cause == null ? new MetadataResolvingException(message, INVALID_METADATA_KEY)
        : new MetadataResolvingException(message, INVALID_METADATA_KEY, cause);
  }

  private Optional<QueryParameterModelProperty> getQueryModelProperty() {
    return component.getAllParameterModels().stream()
        .filter(p -> p.getModelProperty(QueryParameterModelProperty.class).isPresent())
        .map(p -> p.getModelProperty(QueryParameterModelProperty.class).get())
        .findAny();
  }

  /**
   * @return whether the resolver needs a {@link MetadataKey} or not
   */
  public boolean isKeyLess() {
    return keyParts.isEmpty();
  }

  public boolean isKeyRequired() {
    return keyParts.stream().anyMatch(ParameterModel::isRequired);
  }

  private abstract class KeyMetadataTypeVisitor extends MetadataTypeVisitor {

    private final Reference<Object> keyValueHolder = new Reference<>();
    private final Reference<MetadataResolvingException> exceptionValueHolder = new Reference<>();
    private String id;
    private Class metadataKeyType;

    public KeyMetadataTypeVisitor(String id, Class metadataKeyType) {
      this.id = id;
      this.metadataKeyType = metadataKeyType;
    }

    @Override
    protected void defaultVisit(MetadataType metadataType) {
      exceptionValueHolder.set(buildException(format("'%s' type is invalid for MetadataKeyId parameters, "
          + "use String type instead. Affecting component: '%s'",
                                                     metadataKeyType.getSimpleName(), component.getName())));
    }

    @Override
    public void visitBoolean(BooleanType booleanType) {
      keyValueHolder.set(Boolean.valueOf(id));
    }

    @Override
    public void visitString(StringType stringType) {
      if (metadataKeyType.isEnum()) {
        keyValueHolder.set(Enum.valueOf(metadataKeyType, id));
      } else if (getQueryModelProperty().isPresent() && isDsqlQuery(id)) {
        DsqlQuery dsqlQuery = dsqlParser.get().parse(id);
        keyValueHolder.set(dsqlQuery);
      } else {
        keyValueHolder.set(id);
      }
    }

    @Override
    public void visitObject(ObjectType objectType) {
      try {
        keyValueHolder.set(instantiateFromFieldValue(metadataKeyType, getFieldValuesMap()));
      } catch (MetadataResolvingException e) {
        exceptionValueHolder.set(e);
      }
    }

    protected abstract Map<Field, String> getFieldValuesMap() throws MetadataResolvingException;

    public Object getResultId() throws MetadataResolvingException {
      if (exceptionValueHolder.get() != null) {
        throw exceptionValueHolder.get();
      }
      return keyValueHolder.get();
    }
  }

  private List<ParameterModel> getMetadataKeyPartsAndPopulateIndex(ComponentModel componentModel) {
    return componentModel.getAllParameterModels().stream()
        .filter(p -> p.getModelProperty(MetadataKeyPartModelProperty.class).isPresent())
        .peek(p -> parameterModelIndex.put(p.getName(), p))
        .sorted((p1, p2) -> {
          Optional<MetadataKeyPartModelProperty> mk1 = p1.getModelProperty(MetadataKeyPartModelProperty.class);
          Optional<MetadataKeyPartModelProperty> mk2 = p2.getModelProperty(MetadataKeyPartModelProperty.class);
          return mk1.get().getOrder() - mk2.get().getOrder();
        })
        .collect(toList());
  }
}
