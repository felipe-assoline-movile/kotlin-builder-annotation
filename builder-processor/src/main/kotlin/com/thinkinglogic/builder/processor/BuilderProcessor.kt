package com.thinkinglogic.builder.processor

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.thinkinglogic.builder.annotation.Builder
import org.jetbrains.annotations.NotNull
import java.io.File
import java.util.stream.Collectors
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.lang.model.type.ArrayType
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.PrimitiveType
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.ElementFilter.fieldsIn
import javax.tools.Diagnostic.Kind.ERROR
import javax.tools.Diagnostic.Kind.NOTE

/**
 * Kapt processor for the @Builder annotation.
 * Constructs a Builder for the annotated class.
 */
@SupportedAnnotationTypes("com.thinkinglogic.builder.annotation.Builder")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedOptions(BuilderProcessor.KAPT_KOTLIN_GENERATED_OPTION_NAME)
class BuilderProcessor : AbstractProcessor() {

    companion object {
        const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"
        const val CHECK_REQUIRED_FIELDS_FUNCTION_NAME = "checkRequiredFields"
    }

    override fun process(annotations: MutableSet<out TypeElement>?, roundEnv: RoundEnvironment): Boolean {
        val annotatedElements = roundEnv.getElementsAnnotatedWith(Builder::class.java)
        if (annotatedElements.isEmpty()) {
            processingEnv.noteMessage { "No classes annotated with @${Builder::class.java.simpleName} in this round ($roundEnv)" }
            return false
        }

        val generatedSourcesRoot = processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME] ?: run {
            processingEnv.errorMessage { "Can't find the target directory for generated Kotlin files." }
            return false
        }

        processingEnv.noteMessage { "Generating Builders for ${annotatedElements.size} classes in $generatedSourcesRoot" }

        val sourceRootFile = File(generatedSourcesRoot)
        sourceRootFile.mkdir()

        annotatedElements.forEach { annotatedClass ->
            if (annotatedClass !is TypeElement) {
                annotatedClass.errorMessage { "Invalid element type, expected a class" }
                return@forEach
            }

            writeBuilderForClass(annotatedClass, sourceRootFile)
        }

        return false
    }

    private fun writeBuilderForClass(annotatedClass: TypeElement, sourceRootFile: File) {
        val packageName = processingEnv.elementUtils.getPackageOf(annotatedClass).toString()
        val builderClassName = "${annotatedClass.simpleName}Builder"

        processingEnv.noteMessage { "Writing $packageName.$builderClassName" }

        val builderSpec = TypeSpec.classBuilder(builderClassName)
        val builderClass = ClassName(packageName, builderClassName)
        val fieldsInClass = fieldsIn(processingEnv.elementUtils.getAllMembers(annotatedClass))

        fieldsInClass.forEach { field ->
            processingEnv.noteMessage { "Adding field: $field" }
            builderSpec.addProperty(field.asProperty())
            builderSpec.addFunction(field.asSetterFunctionReturning(builderClass))
        }

        builderSpec.addFunction(createBuildFunction(fieldsInClass, annotatedClass))
        builderSpec.addFunction(createCheckRequiredFieldsFunction(fieldsInClass))

        FileSpec.builder(packageName, builderClassName)
                .addType(builderSpec.build())
                .build()
                .writeTo(sourceRootFile)
    }

    private fun createBuildFunction(fieldInClass: List<Element>, returnType: TypeElement): FunSpec {
        val code = StringBuilder("$CHECK_REQUIRED_FIELDS_FUNCTION_NAME()")
        code.appendln().append("return ${returnType.simpleName}(")
        val iterator = fieldInClass.listIterator()
        while (iterator.hasNext()) {
            val field = iterator.next()
            code.appendln().append("    ${field.simpleName} = ${field.simpleName}")
            if (!field.isNullable()) {
                code.append("!!")
            }
            if (iterator.hasNext()) {
                code.append(",")
            }
        }
        code.appendln().append(")").appendln()

        return FunSpec.builder("build")
                .returns(returnType.asClassName())
                .addCode(code.toString())
                .build()
    }

    private fun createCheckRequiredFieldsFunction(fieldInClass: List<Element>): FunSpec {
        val code = StringBuilder()
        fieldInClass
                .filterNot { it.isNullable() }
                .forEach { field ->
                    code.append("    check(${field.simpleName} != null, {\"${field.simpleName} must not be null\"})").appendln()
                }

        return FunSpec.builder(CHECK_REQUIRED_FIELDS_FUNCTION_NAME)
                .addCode(code.toString())
                .addModifiers(KModifier.PRIVATE)
                .build()
    }

    private fun Element.asKotlinTypeName(): TypeName {
        return asType().asKotlinTypeName()
    }

    private fun TypeMirror.asKotlinTypeName(): TypeName {
        return when (this) {
            is PrimitiveType -> processingEnv.typeUtils.boxedClass(this as PrimitiveType?).asKotlinClassName()
            is ArrayType -> {
                val arrayClass = ClassName("kotlin", "Array")
                return arrayClass.parameterizedBy(this.componentType.asKotlinTypeName())
            }
            is DeclaredType -> {
                val typeName = this.asTypeElement().asKotlinClassName()
                if (!this.typeArguments.isEmpty()) {
                    val kotlinTypeArguments = typeArguments.stream()
                            .map { it.asKotlinTypeName() }
                            .collect(Collectors.toList())
                            .toTypedArray()
                    return typeName.parameterizedBy(*kotlinTypeArguments)
                }
                return typeName
            }
            else -> this.asTypeElement().asKotlinClassName()
        }
    }

    private fun TypeElement.asKotlinClassName(): ClassName {
        val className = asClassName()
        return try {
            // ensure that java.lang.* and java.util.* etc classes are converted to their kotlin equivalents
            Class.forName(className.canonicalName).kotlin.asClassName()
        } catch (e: ClassNotFoundException) {
            // probably part of the same source tree as the annotated class
            className
        }
    }

    private fun TypeMirror.asTypeElement() = processingEnv.typeUtils.asElement(this) as TypeElement

    private fun Element.isNullable(): Boolean {
        if (this.asType() is PrimitiveType) {
            return false
        }
        return !this.annotationMirrors
                .map { it.annotationType.toString() }
                .toSet()
                .contains(NotNull::class.java.name)
    }

    private fun Element.asProperty(): PropertySpec =
            PropertySpec.varBuilder(simpleName.toString(), asKotlinTypeName().asNullable(), KModifier.PRIVATE)
                    .initializer("null")
                    .build()

    private fun Element.asSetterFunctionReturning(returnType: ClassName): FunSpec {
        val fieldType = asKotlinTypeName()
        val parameterClass = if (isNullable()) {
            fieldType.asNullable()
        } else {
            fieldType
        }
        return FunSpec.builder(simpleName.toString())
                .addParameter(ParameterSpec.builder("value", parameterClass).build())
                .returns(returnType)
                .addCode("return apply { $simpleName = value }\n")
                .build()
    }

    private fun Element.errorMessage(message: () -> String) {
        processingEnv.messager.printMessage(ERROR, message(), this)
    }
}

private fun ProcessingEnvironment.errorMessage(message: () -> String) {
    this.messager.printMessage(ERROR, message())
}

private fun ProcessingEnvironment.noteMessage(message: () -> String) {
    this.messager.printMessage(NOTE, message())
}