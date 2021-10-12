/**
 * Copyright 2012-2020 The Feign Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import feign.Request.HttpMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static feign.Util.checkState;
import static feign.Util.emptyToNull;

/**
 * Defines what annotations and values are valid on interfaces.
 * 用于解析Feign中的注解
 */
public interface Contract {

  /**
   * Called to parse the methods in the class that are linked to HTTP requests.
   *
   * @param targetType {@link feign.Target#type() type} of the Feign interface.
   */
  List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType);

  abstract class BaseContract implements Contract {

    private static void checkMapString(String name, Class<?> type, Type genericType) {
      checkState(Map.class.isAssignableFrom(type),
          "%s parameter must be a Map: %s", name, type);
      checkMapKeys(name, genericType);
    }

    private static void checkMapKeys(String name, Type genericType) {
      Class<?> keyClass = null;

      // assume our type parameterized
      if (ParameterizedType.class.isAssignableFrom(genericType.getClass())) {
        final Type[] parameterTypes = ((ParameterizedType) genericType).getActualTypeArguments();
        keyClass = (Class<?>) parameterTypes[0];
      } else if (genericType instanceof Class<?>) {
        // raw class, type parameters cannot be inferred directly, but we can scan any extended
        // interfaces looking for any explict types
        final Type[] interfaces = ((Class) genericType).getGenericInterfaces();
        if (interfaces != null) {
          for (final Type extended : interfaces) {
            if (ParameterizedType.class.isAssignableFrom(extended.getClass())) {
              // use the first extended interface we find.
              final Type[] parameterTypes = ((ParameterizedType) extended).getActualTypeArguments();
              keyClass = (Class<?>) parameterTypes[0];
              break;
            }
          }
        }
      }

      if (keyClass != null) {
        checkState(String.class.equals(keyClass),
            "%s key must be a String: %s", name, keyClass.getSimpleName());
      }
    }

    /**
     * @param targetType {@link feign.Target#type() type} of the Feign interface.
     * @see #parseAndValidateMetadata(Class)
     */
    @Override
    public List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType) {
      // 检查TypeParameters数据和Interfaces数据，不符合抛出异常
      checkState(targetType.getTypeParameters().length == 0, "Parameterized types unsupported: %s",
          targetType.getSimpleName());
      checkState(targetType.getInterfaces().length <= 1, "Only single inheritance supported: %s",
          targetType.getSimpleName());
      if (targetType.getInterfaces().length == 1) {
        checkState(targetType.getInterfaces()[0].getInterfaces().length == 0,
            "Only single-level inheritance supported: %s",
            targetType.getSimpleName());
      }
      // 创建用于接收处理结果的对象
      final Map<String, MethodMetadata> result = new LinkedHashMap<String, MethodMetadata>();
      // 循环当前目标类中的方法集合
      for (final Method method : targetType.getMethods()) {
        // 如果方法所在的类是Object并且修饰符为static或者是默认的跳过处理
        if (method.getDeclaringClass() == Object.class ||
            (method.getModifiers() & Modifier.STATIC) != 0 ||
            Util.isDefault(method)) {
          continue;
        }
        // 解析单个方法的方法元数据
        final MethodMetadata metadata = parseAndValidateMetadata(targetType, method);
        // 检查数据
        checkState(!result.containsKey(metadata.configKey()), "Overrides unsupported: %s",
            metadata.configKey());
        // 将数据写入到接收集合中
        result.put(metadata.configKey(), metadata);
      }
      // 将接收集合中的value值集合返回
      return new ArrayList<>(result.values());
    }

    /**
     * @deprecated use {@link #parseAndValidateMetadata(Class, Method)} instead.
     */
    @Deprecated
    public MethodMetadata parseAndValidateMetadata(Method method) {
      return parseAndValidateMetadata(method.getDeclaringClass(), method);
    }

    /**
     * Called indirectly by {@link #parseAndValidateMetadata(Class)}.
     */
    protected MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
      // 创建方法元数据对象
      final MethodMetadata data = new MethodMetadata();
      // 设置目标对象
      data.targetType(targetType);
      // 设置方法对象
      data.method(method);
      // 设置返回值类型
      data.returnType(Types.resolve(targetType, targetType, method.getGenericReturnType()));
      // 设置configKey，生成策略：类路径+#+方法名称+参数集合信息
      data.configKey(Feign.configKey(targetType, method));

      // 解析目标类上存在的接口
      if (targetType.getInterfaces().length == 1) {
        // 处理接口上的注解
        processAnnotationOnClass(data, targetType.getInterfaces()[0]);
      }
      // 处理目标对象上的注解
      processAnnotationOnClass(data, targetType);


      // 循环处理方法上的注解
      for (final Annotation methodAnnotation : method.getAnnotations()) {
        processAnnotationOnMethod(data, methodAnnotation, method);
      }
      // 元数据是否需要忽略处理如果需要则将当前处理的方法元数据返回，不做额外处理。
      if (data.isIgnored()) {
        return data;
      }
      // 检查data对象中的template对象是否有HTTP方法，如果没有抛出异常
      checkState(data.template().method() != null,
          "Method %s not annotated with HTTP method type (ex. GET, POST)%s",
          data.configKey(), data.warnings());
      // 获取方法参数上的参数类型集合
      final Class<?>[] parameterTypes = method.getParameterTypes();
      // 获取方法参数上的通用类型集合
      final Type[] genericParameterTypes = method.getGenericParameterTypes();

      // 获取方法参数上的注解集合
      final Annotation[][] parameterAnnotations = method.getParameterAnnotations();
      // 获取参数数量
      final int count = parameterAnnotations.length;
      for (int i = 0; i < count; i++) {
        // 是否是http注解标记
        boolean isHttpAnnotation = false;
        if (parameterAnnotations[i] != null) {
          // 确认是否是http注解
          isHttpAnnotation = processAnnotationsOnParameter(data, parameterAnnotations[i], i);
        }

        // 如果是http注解
        if (isHttpAnnotation) {
          // 将parameterToIgnore中的数据设置当前索引
          data.ignoreParamater(i);
        }

        // 如果参数类型搜索URI
        if (parameterTypes[i] == URI.class) {
          // 设置url索引为当前索引
          data.urlIndex(i);
        }
        // 不是http注解并且当前参数类型不是Request.Options
        else if (!isHttpAnnotation && parameterTypes[i] != Request.Options.class) {
          // 确认是否已处理
          if (data.isAlreadyProcessed(i)) {
            // 如果已处理并且不满足两个条件中的一个抛出异常，条件一：表单参数为空，条件二：bodyIndex为空
            checkState(data.formParams().isEmpty() || data.bodyIndex() == null,
                "Body parameters cannot be used with form parameters.%s", data.warnings());
          } else {
            checkState(data.formParams().isEmpty(),
                "Body parameters cannot be used with form parameters.%s", data.warnings());
            checkState(data.bodyIndex() == null,
                "Method has too many Body parameters: %s%s", method, data.warnings());
            // 设置bodyIndex为当前索引
            data.bodyIndex(i);
            // 设置bodyType
            data.bodyType(Types.resolve(targetType, targetType, genericParameterTypes[i]));
          }
        }
      }

      // 如果headerMapIndex不为空进行数据检查，如果检查失败抛出异常
      if (data.headerMapIndex() != null) {
        checkMapString("HeaderMap", parameterTypes[data.headerMapIndex()],
            genericParameterTypes[data.headerMapIndex()]);
      }

      // 如果queryMapIndex不为空，如果检查失败抛出异常
      if (data.queryMapIndex() != null) {
        if (Map.class.isAssignableFrom(parameterTypes[data.queryMapIndex()])) {
          checkMapKeys("QueryMap", genericParameterTypes[data.queryMapIndex()]);
        }
      }

      return data;
    }

    /**
     * Called by parseAndValidateMetadata twice, first on the declaring class, then on the target
     * type (unless they are the same).
     *
     * @param data metadata collected so far relating to the current java method.
     * @param clz the class to process
     */
    protected abstract void processAnnotationOnClass(MethodMetadata data, Class<?> clz);

    /**
     * @param data metadata collected so far relating to the current java method.
     * @param annotation annotations present on the current method annotation.
     * @param method method currently being processed.
     */
    protected abstract void processAnnotationOnMethod(MethodMetadata data,
                                                      Annotation annotation,
                                                      Method method);

    /**
     * @param data metadata collected so far relating to the current java method.
     * @param annotations annotations present on the current parameter annotation.
     * @param paramIndex if you find a name in {@code annotations}, call
     *        {@link #nameParam(MethodMetadata, String, int)} with this as the last parameter.
     * @return true if you called {@link #nameParam(MethodMetadata, String, int)} after finding an
     *         http-relevant annotation.
     */
    protected abstract boolean processAnnotationsOnParameter(MethodMetadata data,
                                                             Annotation[] annotations,
                                                             int paramIndex);

    /**
     * links a parameter name to its index in the method signature.
     */
    protected void nameParam(MethodMetadata data, String name, int i) {
      final Collection<String> names =
          data.indexToName().containsKey(i) ? data.indexToName().get(i) : new ArrayList<String>();
      names.add(name);
      data.indexToName().put(i, names);
    }
  }


  class Default extends DeclarativeContract {

    static final Pattern REQUEST_LINE_PATTERN = Pattern.compile("^([A-Z]+)[ ]*(.*)$");

    public Default() {
      // 注册类上的Headers注解信息
      super.registerClassAnnotation(Headers.class, (Headers header, MethodMetadata data) -> {
        // 获取Headers注解中的value数据
        final String[] headersOnType = header.value();
        // 检查Headers注解中的value数据是否存在，如果不存在抛出异常
        checkState(headersOnType.length > 0, "Headers annotation was empty on type %s.",
            data.configKey());
        // 注解数据转换成map
        final Map<String, Collection<String>> headers = toMap(headersOnType);
        // 将date对象中的数据加入到转换后的map对象中
        headers.putAll(data.template().headers());
        // 清理data对象中的头信息并且将处理好的头信息放入data数据中
        data.template().headers(null); // to clear
        data.template().headers(headers);
      });

      // 注册方法上的RequestLine注解信息
      super.registerMethodAnnotation(RequestLine.class, (RequestLine ann, MethodMetadata data) -> {
        // 获取RequestLine注解的value数据
        final String requestLine = ann.value();
        // 如果RequestLine注解的value数据数据为空抛出异常
        checkState(emptyToNull(requestLine) != null,
            "RequestLine annotation was empty on method %s.", data.configKey());

        // 正则匹配
        final Matcher requestLineMatcher = REQUEST_LINE_PATTERN.matcher(requestLine);
        // 如果未匹配则抛出异常
        if (!requestLineMatcher.find()) {
          throw new IllegalStateException(String.format(
              "RequestLine annotation didn't start with an HTTP verb on method %s",
              data.configKey()));
        }
        // 匹配的情况下处理
        else {
          // 设置请求方式
          data.template().method(HttpMethod.valueOf(requestLineMatcher.group(1)));
          // 设置路由地址
          data.template().uri(requestLineMatcher.group(2));
        }
        // 设置是否需要编码斜杠
        data.template().decodeSlash(ann.decodeSlash());
        // 设置CollectionFormat数据
        data.template()
            .collectionFormat(ann.collectionFormat());
      });
      // 注册方法上的Body注解信息
      super.registerMethodAnnotation(Body.class, (Body ann, MethodMetadata data) -> {
        // 获取Body注解中的value数据
        final String body = ann.value();
        // 如果Body注解的value数据数据为空抛出异常
        checkState(emptyToNull(body) != null, "Body annotation was empty on method %s.",
            data.configKey());
        // 确认Body注解中的value数据是否包含"{",如果不包含直接设置到template对象之中的body，如果包含则设置到template对象中的bodyTemplate中
        if (body.indexOf('{') == -1) {
          data.template().body(body);
        } else {
          data.template().bodyTemplate(body);
        }
      });
      // 注册方法上的Headers注解信息
      super.registerMethodAnnotation(Headers.class, (Headers header, MethodMetadata data) -> {
        // 获取Headers注解中的value数据
        final String[] headersOnMethod = header.value();
        // 如果Headers注解的value数据数据为空抛出异常
        checkState(headersOnMethod.length > 0, "Headers annotation was empty on method %s.",
            data.configKey());
        // 将Headers注解中的value数据转换成map后放入到template对象的头信息中
        data.template().headers(toMap(headersOnMethod));
      });

      // 注册方法参数上的Param注解信息
      super.registerParameterAnnotation(Param.class,
          (Param paramAnnotation, MethodMetadata data, int paramIndex) -> {
            // 获取Param注解的value数据
            final String annotationName = paramAnnotation.value();
            // 根据索引获取方法参数对象
            final Parameter parameter = data.method().getParameters()[paramIndex];
            // 参数名称确认，如果Param注解数据存在则采用注解数据，如果不存在则使用参数名称本身的数据
            final String name;
            if (emptyToNull(annotationName) == null && parameter.isNamePresent()) {
              name = parameter.getName();
            } else {
              name = annotationName;
            }
            // 如果参数名称不存在抛出异常
            checkState(emptyToNull(name) != null, "Param annotation was empty on param %s.",
                paramIndex);
            // 设置方法数据信息，索引和方法参数名称
            nameParam(data, name, paramIndex);
            // 提取Param注解中的Param.Expander数据
            final Class<? extends Param.Expander> expander = paramAnnotation.expander();
            // 确认类型不是Param.ToStringExpander的情况下加入data中的indexToExpanderClass变量
            if (expander != Param.ToStringExpander.class) {
              data.indexToExpanderClass().put(paramIndex, expander);
            }
            // 如果data对象中的template对象不存在请求参数时需要将方法参数名称加入到formParams对象
            if (!data.template().hasRequestVariable(name)) {
              data.formParams().add(name);
            }
          });
      // 注册方法参数上的QueryMap注解信息
      super.registerParameterAnnotation(QueryMap.class,
          (QueryMap queryMap, MethodMetadata data, int paramIndex) -> {
            // 如果data对象中的queryMapIndex数据不为空抛出异常
            checkState(data.queryMapIndex() == null,
                "QueryMap annotation was present on multiple parameters.");
            // 设置索引
            data.queryMapIndex(paramIndex);
            // 设置是否已经编码
            data.queryMapEncoded(queryMap.encoded());
          });
      // 注册方法参数上的HeaderMap注解信息
      super.registerParameterAnnotation(HeaderMap.class,
          (HeaderMap queryMap, MethodMetadata data, int paramIndex) -> {
            // 如果data对象中的headerMapIndex对象不为null则抛出异常
            checkState(data.headerMapIndex() == null,
                "HeaderMap annotation was present on multiple parameters.");
            // 设置headerMapIndex数据
            data.headerMapIndex(paramIndex);
          });

    }

    private static Map<String, Collection<String>> toMap(String[] input) {
      final Map<String, Collection<String>> result =
          new LinkedHashMap<String, Collection<String>>(input.length);
      for (final String header : input) {
        final int colon = header.indexOf(':');
        final String name = header.substring(0, colon);
        if (!result.containsKey(name)) {
          result.put(name, new ArrayList<String>(1));
        }
        result.get(name).add(header.substring(colon + 1).trim());
      }
      return result;
    }

  }
}
