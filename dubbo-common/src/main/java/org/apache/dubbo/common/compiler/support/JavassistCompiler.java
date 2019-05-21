/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.compiler.support;

import org.apache.dubbo.common.utils.ClassHelper;

import javassist.CtClass;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavassistCompiler. (SPI, Singleton, ThreadSafe)
 * 实现 AbstractCompiler 抽象类，基于 Javassist 实现的 Compiler
 *
 *
 * Javassist 是一个开源的分析、编辑和创建 Java 字节码的类库。通过使用Javassist 对字节码操作可以实现动态 ”AOP” 框架。
 * 关于 Java 字节码的处理，目前有很多工具，如 bcel，asm( cglib只是对asm又封装了一层 )。不过这些都需要直接跟虚拟机指令打交道。
 * Javassist 的主要的优点，在于简单，而且快速，直接使用 Java 编码的形式，而不需要了解虚拟机指令，就能动态改变类的结构，或者动态生成类。
 */
public class JavassistCompiler extends AbstractCompiler {

    /**
     * 正则 - 匹配 import
     */
    private static final Pattern IMPORT_PATTERN = Pattern.compile("import\\s+([\\w\\.\\*]+);\n");

    /**
     * 正则 - 匹配 extends
     */
    private static final Pattern EXTENDS_PATTERN = Pattern.compile("\\s+extends\\s+([\\w\\.]+)[^\\{]*\\{\n");

    /**
     * 正则 - 匹配 implements
     */
    private static final Pattern IMPLEMENTS_PATTERN = Pattern.compile("\\s+implements\\s+([\\w\\.]+)\\s*\\{\n");

    /**
     * 正则 - 匹配方法
     */
    private static final Pattern METHODS_PATTERN = Pattern.compile("\n(private|public|protected)\\s+");

    /**
     * 正则 - 匹配变量
     */
    private static final Pattern FIELD_PATTERN = Pattern.compile("[^\n]+=[^\n]+;");

    @Override
    /**
     * 因为传入的是 Java 源代码 source ，需要通过正则匹配出 import、extends、implements、方法、变量，传递给 Javassist API ，进行类生成。
     */
    public Class<?> doCompile(String name, String source) throws Throwable {
        CtClassBuilder builder = new CtClassBuilder();
        builder.setClassName(name);

        // process imported classes
        // 匹配 import
        Matcher matcher = IMPORT_PATTERN.matcher(source);
        while (matcher.find()) {
            builder.addImports(matcher.group(1).trim());
        }
        
        // process extended super class
        // 匹配 extends
        matcher = EXTENDS_PATTERN.matcher(source);
        if (matcher.find()) {
            builder.setSuperClassName(matcher.group(1).trim());
        }
        
        // process implemented interfaces
        matcher = IMPLEMENTS_PATTERN.matcher(source);
        if (matcher.find()) {
            String[] ifaces = matcher.group(1).trim().split("\\,");
            Arrays.stream(ifaces).forEach(i -> builder.addInterface(i.trim()));
        }
        
        // process constructors, fields, methods
        // 获得类中的内容，即首末 {} 的内容。
        String body = source.substring(source.indexOf('{') + 1, source.length() - 1);
        // 匹配 method 。使用分隔的方式，实际上，分隔出来的不仅仅有方法。
        String[] methods = METHODS_PATTERN.split(body);
        String className = ClassUtils.getSimpleClassName(name);
        Arrays.stream(methods).map(String::trim).filter(m -> !m.isEmpty()).forEach(method-> {
            if (method.startsWith(className)) {  // 构造方法
                builder.addConstructor("public " + method);
            } else if (FIELD_PATTERN.matcher(method).matches()) {  // 变量
                builder.addField("private " + method);
            } else {   // 方法
                builder.addMethod("public " + method);
            }
        });
        
        // compile
        // 生成类
        // JavassistCompiler.class.getProtectionDomain() =》 设置保护域和 JavassistCompiler 一致，即 `#getClass()` 方法。
        // 深入见 《Java安全——安全管理器、访问控制器和类装载器》https://www.zybuluo.com/changedi/note/417132
        ClassLoader classLoader = ClassHelper.getCallerClassLoader(getClass());
        CtClass cls = builder.build(classLoader);
        return cls.toClass(classLoader, JavassistCompiler.class.getProtectionDomain());  // 生成类
    }

}
