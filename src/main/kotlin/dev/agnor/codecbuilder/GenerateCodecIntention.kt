package dev.agnor.codecbuilder

import com.intellij.lang.jvm.JvmModifier
import com.intellij.lang.jvm.types.JvmPrimitiveTypeKind
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.parentOfType
import com.intellij.util.IncorrectOperationException
import dev.agnor.codecbuilder.lang.SourceProcessor
import dev.agnor.codecbuilder.preprocessor.allMethods
import dev.agnor.codecbuilder.preprocessor.constructors
import dev.agnor.codecbuilder.psiwrapper.Method
import dev.agnor.codecbuilder.psiwrapper.Parameter
import kotlin.streams.toList

const val OPTIONAL_MARKER = '§'
private const val UNKNOWN_CODEC = "UNKNOWN_CODEC"
private const val UNKNOWN_PRIMITIVE_CODEC = "UNKNOWN_PRIMITIVE_CODEC"
private const val UNKNOWN_CLASS_CODEC = "UNKNOWN_CLASS_CODEC"
private const val MISSING_PRIMITIVE_STREAM_CODEC = "MISSING_PRIMITIVE_STREAM_CODEC"
private const val MULTI_DIMENSIONAL_ARRAY_CODEC = "MULTI_DIMENSIONAL_ARRAY_CODEC"
private const val MISSING_GETTER = "(MISSING_GETTER)"
public val ERROR_CODECS = setOf(
    UNKNOWN_CODEC,
    UNKNOWN_PRIMITIVE_CODEC,
    UNKNOWN_CLASS_CODEC,
    MISSING_PRIMITIVE_STREAM_CODEC,
    MULTI_DIMENSIONAL_ARRAY_CODEC
)

@Suppress("UnstableApiUsage")
class GenerateCodecIntention : CodecBuilderIntention() {
    private class CodecSource(val fqn: String, val preferred: Set<String>)

    override fun getText() = "Generate Codec for type"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        return findClass(project, element) != null
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val clazz = findClass(project, element)!!
        val file = element.parentOfType<PsiFile>()!!
        val fileClazz = SourceProcessor.sourceProcessors
            .flatMap { it.findClassesInBody(file, project) }
            .firstOrNull{cls -> cls.first.name == file.name.split(".")[0]}
        if (fileClazz == null) {
            notify("Missing Class of same name in current file", NotificationType.WARNING, project)
            return
        }
        val expression = generateForClass(project, clazz, fileClazz.first, fileClazz.second)

        fileClazz.second.write(fileClazz.first, project, expression, file)

        if (expression.let { text -> ERROR_CODECS.none { text.contains(it) } }) {
            notify("Successfully generated codec", NotificationType.INFORMATION, project)
        } else {
            notify("Generated codec with some issues", NotificationType.WARNING, project)
        }
    }

    private fun getRegistrySources(): List<String> {
        val listOf = listOf("net.minecraft.core.registries.BuiltInRegistries", "net.neoforged.neoforge.registries.NeoForgeRegistries")
        return listOf + getStoredCodecRoots()
    }

    private fun getCodecSources(clazz: PsiClass, source: PsiClass): List<CodecSource> {
        val sources = mutableListOf<CodecSource>()
        sources.add(CodecSource(clazz.qualifiedName!!, setOf("CODEC")))
        sources.add(CodecSource(CODEC_FQN, setOf()))
        sources.add(CodecSource(source.qualifiedName!!, setOf()))
        sources.add(CodecSource("net.minecraft.util.ExtraCodecs", setOf("JSON", "QUATERNIONF")))
        sources.addAll(getStoredCodecRoots().stream().map { root -> CodecSource(root, setOf()) }.toList())
        return sources
    }

    private fun notify(text: String, type: NotificationType, project: Project) {

        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("CodecConstructionComplete")
        notificationGroup.createNotification(text, type).notify(project)
    }

    private fun generateForClass(project: Project, target: PsiClass, source: PsiClass, sourceProcessor: SourceProcessor<out PsiElement, out PsiElement>): String {
        val constructors = constructors(target)
        val factories = allMethods(target).filter {
            it.isStatic && PsiTypesUtil.getPsiClass(it.returnType) == target
        }
        val allConstructors = (constructors + factories).sortedByDescending { it.parameters.count()}
        val candidates = allConstructors.map { Pair(it.name, toMembers(project, it, target, source, sourceProcessor)) }
        val record = SourceProcessor.sourceProcessors
            .mapNotNull {
                sourceProcessor.findRecordConstructor(target, project, source, ::getCodec)
            }
            .firstOrNull()
        if (record != null) {
            return generateMember(project, target, record.first, record.second, sourceProcessor)
        }
        if (candidates.isEmpty()) {
            return generateUnitCodec(project, target, sourceProcessor);
        }
        candidates.sortedBy { it.second.size } // sort this to prefer constructors with less parameters if they are missing the same amount of codecs/getters or to find the shorter valid codec
        val candidate = candidates.minByOrNull { candidate ->
            candidate.second.sumOf { member -> ERROR_CODECS.count { member.codec.contains(it) } + (if (member.getter == MISSING_GETTER) 1 else 0) }
        } ?: throw IncorrectOperationException()
        return generateMember(project, target, candidate.first, candidate.second, sourceProcessor)
    }

    private fun generateMember(project: Project, target: PsiClass, constructor: String, member: List<Member>, sourceProcessor: SourceProcessor<out PsiElement, out PsiElement>): String {
        if (member.isEmpty()) {
            return generateUnitCodec(project, target, sourceProcessor)
        }
        if (member.size <= 16) {
            return generateRCBCodec(project, target, member, sourceProcessor, constructor)
        } else {
            notify("more then 16 elements in codec, 16 elements are the maximum", NotificationType.ERROR, project)
            throw IncorrectOperationException()
        }
    }

    public fun toMembers(project: Project, constructor: Method, target: PsiClass, source: PsiClass, sourceProcessor: SourceProcessor<out PsiElement, out PsiElement>): List<Member> {

        val getters = allMethods(target).filter {
            it.returnType != PsiPrimitiveType.VOID && it.parameters.isEmpty()
        }.sortedBy {
            (it.name.startsWith("get") || it.name.startsWith("is"))&& !it.isDeprecated
        }
        return constructor.parameters.map { parameter ->
            parameter.toMember(project,
                    getters.find { it.returnType == parameter.type && (it.name == parameter.name || it.name == "get${parameter.name.capitalize()}") },
                    target.findFieldByName(parameter.name, true), target, source, sourceProcessor)
        }
    }

    private fun generateUnitCodec(project: Project, clazz: PsiClass, sourceProcessor: SourceProcessor<out PsiElement, out PsiElement>): String {

        return sourceProcessor.generateUnitCodec(clazz)
    }

    private fun generateRCBCodec(project: Project, clazz: PsiClass, members: List<Member>, sourceProcessor: SourceProcessor<out PsiElement, out PsiElement>, constructor: String): String {
        return sourceProcessor.generateRCBCodec(clazz, members, constructor)
    }

    private fun getCodec(project: Project, type: PsiType, source: PsiClass): String {
        val codec = when (type) {
            is PsiPrimitiveType -> getPrimitiveCodec(type)
            is PsiClassType -> getObjectCodec(project, type, source)
            is PsiArrayType -> getArrayCodec(project, type, source)
            else -> UNKNOWN_CODEC
        }
        return codec
    }

    @Suppress("UnstableApiUsage")
    private fun getPrimitiveCodec(type: PsiPrimitiveType) = getPrimitiveCodec(type.kind)

    @Suppress("UnstableApiUsage")
    private fun getPrimitiveCodec(kind: JvmPrimitiveTypeKind) = when (kind) {
        JvmPrimitiveTypeKind.BOOLEAN -> "$CODEC_FQN.BOOL"
        JvmPrimitiveTypeKind.BYTE -> "$CODEC_FQN.BYTE"
        JvmPrimitiveTypeKind.SHORT -> "$CODEC_FQN.SHORT"
        JvmPrimitiveTypeKind.INT -> "$CODEC_FQN.INT"
        JvmPrimitiveTypeKind.LONG -> "$CODEC_FQN.LONG"
        JvmPrimitiveTypeKind.FLOAT -> "$CODEC_FQN.FLOAT"
        JvmPrimitiveTypeKind.DOUBLE -> "$CODEC_FQN.DOUBLE"
        JvmPrimitiveTypeKind.CHAR -> "$CODEC_FQN.STRING.comapFlatMap(s -> s.length() != 1 ? com.mojang.serialization.DataResult.error(() -> \"'\" + s + \"' is an invalid symbol (must be 1 character only).\") : com.mojang.serialization.DataResult.success(s.charAt(0)), String::valueOf)"
        else -> UNKNOWN_PRIMITIVE_CODEC
    }

    private fun getObjectCodec(project: Project, type: PsiClassType, source: PsiClass): String {
        val clazz = type.resolve() ?: return UNKNOWN_CLASS_CODEC
        when (clazz.qualifiedName) {
            null -> return UNKNOWN_CLASS_CODEC
            "java.lang.String" -> return "$CODEC_FQN.STRING"
            "java.util.Optional" -> return getCodec(project, type.parameters.first(), source) + OPTIONAL_MARKER
            "java.util.OptionalInt" -> return getPrimitiveCodec(PsiType.INT) + OPTIONAL_MARKER
            "java.util.OptionalLong" -> return getPrimitiveCodec(PsiType.LONG) + OPTIONAL_MARKER
            "java.util.OptionalDouble" -> return getPrimitiveCodec(PsiType.DOUBLE) + OPTIONAL_MARKER
            "com.mojang.datafixers.util.Pair" -> return getPairCodec(type, project, source)
            "com.mojang.datafixers.util.Either" -> return getEitherCodec(type, project, source)
            "java.util.List" -> {
                val listType = type.parameters.first()
                if (listType is PsiClassType) {
                    val inner = PsiUtil.resolveClassInClassTypeOnly(listType)
                    if (inner?.qualifiedName == "com.mojang.datafixers.util.Pair") {
                        val (first, second) = listType.parameters
                        return "com.mojang.serialization.Codec.compoundList(${getCodec(project, first, source)}, ${getCodec(project, second, source)})"
                    }
                }
                return "${getCodec(project, listType, source)}.listOf()"
            }

            "java.util.Map" -> return getMapCodec(type, project, source)
            "java.util.Set" -> return getSetCodec(type, project, source)
            else -> return getObjectCodecFallback(clazz, type, project, source)

        }
    }

    private fun getObjectCodecFallback(targetClass: PsiClass, targetType: PsiClassType, project: Project, source: PsiClass): String {

        val primitive = JvmPrimitiveTypeKind.getKindByFqn(targetClass.qualifiedName)
        if (primitive != null)
            return getPrimitiveCodec(primitive)

        val scope = GlobalSearchScope.allScope(project)
        val javaPsiFacade = JavaPsiFacade.getInstance(project)
        val codecSources = getCodecSources(targetClass, source)
        for (codecSource in codecSources) {
            val from = javaPsiFacade.findClass(codecSource.fqn, scope) ?: continue
            val codecs = getStaticCodecs(from, targetClass, targetType).sortedBy { it.name in codecSource.preferred }
            if (codecs.isEmpty())
                continue
            return "${from.qualifiedName}.${codecs.first().name}"
        }
        if (targetClass.isEnum)
            return getEnumCodec(targetType)

        val isHolder = targetClass.qualifiedName == "net.minecraft.core.Holder"
        val registryCodec = getRegistryCodec(project, targetClass, if (isHolder) targetType.parameters.first() else targetType, isHolder)
        if (registryCodec != null)
            return registryCodec
        return UNKNOWN_CLASS_CODEC
    }

    private fun getPairCodec(type: PsiClassType, project: Project, source: PsiClass): String {
        val (first, second) = type.parameters
        val firstCodec = getCodec(project, first, source)
        val secondCodec = getCodec(project, second, source)
        return "$CODEC_FQN.pair($firstCodec, $secondCodec)"
    }

    private fun getEitherCodec(type: PsiClassType, project: Project, source: PsiClass): String {
        val (first, second) = type.parameters
        val firstCodec = getCodec(project, first, source)
        val secondCodec = getCodec(project, second, source)
        return "$CODEC_FQN.either($firstCodec, $secondCodec)"
    }

    private fun getMapCodec(type: PsiClassType, project: Project, source: PsiClass): String {
        val (first, second) = type.parameters
        val firstCodec = getCodec(project, first, source)
        val secondCodec = getCodec(project, second, source)
        return "$CODEC_FQN.unboundedMap($firstCodec, $secondCodec)"
    }

    private fun getSetCodec(type: PsiClassType, project: Project, source: PsiClass): String {
        val setType = type.parameters.first()
        return "${getCodec(project, setType, source)}.listOf().xmap(java.util.Set::copyOf, java.util.List::copyOf)"
    }

    private fun getEnumCodec(type: PsiClassType): String {
        return "$CODEC_FQN.STRING.xmap(str -> Objects.requireNonNull(" + type.name + ".valueOf(str)), " + type.name + "::name)"
    }

    @Suppress("UnstableApiUsage")
    private fun getArrayCodec(project: Project, type: PsiArrayType, source: PsiClass): String {
        when (val componentType = type.componentType) {
            is PsiPrimitiveType -> {
                if (componentType.kind !in setOf(JvmPrimitiveTypeKind.DOUBLE, JvmPrimitiveTypeKind.INT, JvmPrimitiveTypeKind.LONG)) {
                    return MISSING_PRIMITIVE_STREAM_CODEC
                }
                return "${getCodec(project, componentType, source)}.listOf().xmap(list -> list.stream().mapTo${componentType.name.capitalize()}(val -> val).toArray(), arr -> java.util.Arrays.stream(arr).boxed().toList())"
            }

            is PsiClassType -> return "${getCodec(project, componentType, source)}.listOf().xmap(list -> list.toArray(new ${componentType.name}[0]), arr -> java.util.Arrays.stream(arr).toList())"
            else -> return MULTI_DIMENSIONAL_ARRAY_CODEC
        }
    }

    @Suppress("UnstableApiUsage")
    private fun getRegistryCodec(project: Project, targetClass: PsiClass, type: PsiType, wrapped: Boolean): String? {
        val scope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        val registrySources = getRegistrySources()
        val registry = registrySources.map { facade.findClass(it, scope) }.associateWith { sourceClass ->
                    sourceClass?.fields?.firstOrNull {
                        it.hasModifier(JvmModifier.STATIC) && PsiUtil.isMemberAccessibleAt(it, targetClass) && isRegistryOf(it.type, type)
                    }
                }.entries.firstOrNull { (clazz, field) -> clazz != null && field != null }
        if (registry != null) {
            val (clazz, field) = registry
            return "${clazz!!.qualifiedName}.${field!!.name}.${if (wrapped) "holderByNameCodec" else "byNameCodec"}()"
        }
        return null
    }

    @Suppress("UnstableApiUsage")
    private fun getStaticCodecs(clazz: PsiClass, targetClass: PsiClass, type: PsiClassType) = clazz.fields.filter {
        it.hasModifier(JvmModifier.STATIC) && PsiUtil.isMemberAccessibleAt(it, targetClass) && isCodecOf(it.type, type)
    }

    private fun isCodecOf(type: PsiType, type1: PsiClassType): Boolean {
        if (type !is PsiClassType) return false
        if (PsiUtil.resolveClassInClassTypeOnly(type)?.qualifiedName != "com.mojang.serialization.Codec") return false
        val codecType = type.parameters.firstOrNull() ?: return false
        return codecType == type1
    }

    private fun isRegistryOf(potentialRegistry: PsiType, targetRegistryType: PsiType): Boolean {
        val registryType = getRegistryType(potentialRegistry)
        return registryType == targetRegistryType
    }



    private fun Parameter.toMember(project: Project, getter: Method?, fieldForGetter: PsiField?, clazz: PsiClass, source: PsiClass, sourceProcessor: SourceProcessor<out PsiElement, out PsiElement>): Member {
        val codec = getCodec(project, type, source)
        val optional = codec.endsWith(OPTIONAL_MARKER)
        if (codec.indexOf(OPTIONAL_MARKER) != codec.lastIndexOf(OPTIONAL_MARKER)) {
            throw IncorrectOperationException("Optional in unexpected place")
        }
        var getterString = MISSING_GETTER
        if (fieldForGetter != null && PsiUtil.isMemberAccessibleAt(fieldForGetter, source)) {
            getterString = sourceProcessor.generateGetterLambdaForField(fieldForGetter)
        }
        if (getter != null) {
            getterString = "(${clazz.qualifiedName}::${getter.name})"
        }
        return Member(codec.replace(OPTIONAL_MARKER + "", ""), name, optional, getterString)
    }

    private fun String.capitalize(): String {
        return this.replaceFirstChar {
            it.uppercaseChar()
        }
    }

    private fun getRegistryType(type: PsiType): PsiType? {
        return getGenericType(type, "net.minecraft.core.Registry")
    }
    private fun getGenericType(type: PsiType, targetType: String): PsiType? {
        if (type is PsiClassType) {
            val resolvedType = type.resolve()?: return null
            if (resolvedType.qualifiedName == targetType)
                return type.parameters[0]
        }
        for (superType in type.superTypes) {
            val codecType = getGenericType(superType, targetType);
            if (codecType != null)
                return codecType
        }
        return null
    }
}
