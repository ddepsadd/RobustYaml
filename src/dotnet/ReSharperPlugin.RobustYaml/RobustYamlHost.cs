#if RIDER
using System;
using System.Collections.Generic;
using System.Linq;
using JetBrains.Application.Parts;
using JetBrains.Lifetimes;
using JetBrains.Metadata.Reader.API;
using JetBrains.ProjectModel;
using JetBrains.Rd.Tasks;
using JetBrains.ReSharper.Feature.Services.Protocol;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.Caches;
using JetBrains.ReSharper.Psi.CSharp.Tree;
using JetBrains.ReSharper.Psi.Modules;
using JetBrains.ReSharper.Psi.Resolve;
using JetBrains.ReSharper.Psi.Tree;
using JetBrains.ReSharper.Psi.Util;
using JetBrains.ReSharper.Resources.Shell;
using JetBrains.Rider.Model;

namespace ReSharperPlugin.RobustYaml
{
    [SolutionComponent(Instantiation.ContainerAsyncAnyThreadSafe)]
    public class RobustYamlHost
    {
        private readonly ISolution mySolution;

        public RobustYamlHost(Lifetime lifetime, ISolution solution)
        {
            mySolution = solution;
            solution.GetProtocolSolution().GetRobustYamlModel().TypeFields
                .SetSync((_, query) => Fields(query.ClassName, query.Path));
        }

        private sealed class Field
        {
            public string Name;
            public IType Type;
            public ITypeMember Member;
            public string PrototypeKind;
            public string KeyPrototypeKind;
        }

        private struct Kinds
        {
            public string Key;
            public string Value;
        }

        private struct Values
        {
            public List<string> Key;
            public List<string> Value;
        }

        private RobustFieldsReply Fields(string className, List<string> path)
        {
            using (ReadLockCookie.Create())
            {
                var services = mySolution.GetPsiServices();

                List<ITypeElement> candidates;
                using (CompilationContextCookie.GetExplicitUniversalContextIfNotSet())
                {
                    var universal = services.Symbols.GetSymbolScope(LibrarySymbolScope.FULL, caseSensitive: true);
                    candidates = universal.GetElementsByShortName(className)
                        .OfType<ITypeElement>()
                        .OrderBy(it => (it as ITypeMember)?.GetContainingType() == null ? 0 : 1)
                        .ToList();
                }

                foreach (var candidate in candidates)
                {
                    var reply = FieldsOf(candidate, services, path);
                    if (!reply.Resolved || reply.Fields.Count > 0)
                        return reply;
                }

                return new RobustFieldsReply(candidates.Count > 0, new List<RobustDataField>());
            }
        }

        private RobustFieldsReply FieldsOf(ITypeElement found, IPsiServices services, List<string> path)
        {
            var result = new List<RobustDataField>();

            var context = ResolveContext(found.Module);
            using (context == null
                       ? CompilationContextCookie.GetExplicitUniversalContextIfNotSet()
                       : CompilationContextCookie.GetOrCreate(context))
            {
                var scope = services.Symbols.GetSymbolScope(found.Module, true, true);
                var type = scope.GetTypeElementByCLRName(found.GetClrName()) ?? found;

                var keySegment = false;
                foreach (var segment in path)
                {
                    if (keySegment)
                    {
                        keySegment = false;
                        continue;
                    }

                    var step = Collect(type).FirstOrDefault(it => it.Name == segment);
                    if (step == null)
                        return Resolved(result);

                    if (step.Type.GetPresentableName(type.PresentationLanguage).Contains(Unresolved))
                        return Unbuilt;

                    keySegment = IsDictionary(step.Type, type.Module);
                    type = Unwrap(step.Type, type.Module);
                    if (type == null)
                        return Resolved(result);
                }

                foreach (var field in Collect(type))
                {
                    var presentable = field.Type.GetPresentableName(type.PresentationLanguage);
                    if (presentable.Contains(Unresolved))
                        return Unbuilt;

                    var values = EnumValues(field.Type, type.Module);
                    result.Add(new RobustDataField(
                        field.Name,
                        presentable,
                        Summary(field.Member),
                        field.PrototypeKind,
                        field.KeyPrototypeKind,
                        IsDictionary(field.Type, type.Module),
                        IsSequence(field.Type, type.Module),
                        values.Value,
                        values.Key));
                }
            }

            return Resolved(result);
        }

        private static RobustFieldsReply Resolved(List<RobustDataField> fields) =>
            new RobustFieldsReply(true, fields);

        private static RobustFieldsReply Unbuilt =>
            new RobustFieldsReply(false, new List<RobustDataField>());

        private static string Summary(ITypeMember member)
        {
            var node = member.GetXMLDescriptionSummary(true)
                       ?? member.GetXMLDoc(true)?.SelectSingleNode("descendant-or-self::summary");
            var xml = node?.InnerXml;
            return string.IsNullOrWhiteSpace(xml) ? null : xml;
        }

        private IModuleReferenceResolveContext ResolveContext(IPsiModule module)
        {
            var containing = module.ContainingProjectModule;
            if (containing == null)
                return null;

            return mySolution.GetComponent<PsiModuleResolveContextManager>()
                .GetOrCreateModuleResolveContext(containing, module, module.TargetFrameworkId);
        }

        private static List<Field> Collect(ITypeElement root)
        {
            var result = new List<Field>();
            var seen = new HashSet<string>(StringComparer.Ordinal);
            var visited = new HashSet<string>(StringComparer.Ordinal);

            var queue = new Queue<KeyValuePair<ITypeElement, ISubstitution>>();
            queue.Enqueue(new KeyValuePair<ITypeElement, ISubstitution>(root, EmptySubstitution.INSTANCE));
            visited.Add(root.GetClrName().FullName);

            while (queue.Count > 0 && visited.Count <= MaxTypes)
            {
                var current = queue.Dequeue();
                var type = current.Key;
                var substitution = current.Value;

                var isRecord = HasAttribute(type, DataRecordAttribute);

                foreach (var member in type.GetMembers())
                {
                    if (!(member is ITypeOwner owner))
                        continue;

                    var attribute = DataFieldAttribute(member);
                    if (attribute == null && !(isRecord && IsRecordField(member)))
                        continue;

                    var memberType = substitution.Apply(owner.Type);

                    if (attribute != null && WithoutSuffix(attribute.Name?.ShortName) == IncludeDataField)
                    {
                        var included = memberType as IDeclaredType;
                        var element = included?.GetTypeElement();
                        if (element != null && visited.Add(element.GetClrName().FullName))
                            queue.Enqueue(new KeyValuePair<ITypeElement, ISubstitution>(
                                element, included.GetSubstitution()));
                        continue;
                    }

                    var name = FixedName(attribute)
                               ?? ExplicitName(attribute)
                               ?? Decapitalize(member.ShortName.TrimStart('_'));
                    if (!seen.Add(name))
                        continue;

                    var kinds = PrototypeKinds(memberType, attribute, type.Module);
                    result.Add(new Field
                    {
                        Name = name,
                        Type = memberType,
                        Member = member,
                        PrototypeKind = kinds.Value,
                        KeyPrototypeKind = kinds.Key,
                    });
                }

                foreach (var super in type.GetSuperTypes())
                {
                    var element = super.GetTypeElement();
                    if (element == null || !visited.Add(element.GetClrName().FullName))
                        continue;

                    queue.Enqueue(new KeyValuePair<ITypeElement, ISubstitution>(
                        element, substitution.Apply(super.GetSubstitution())));
                }
            }

            return result;
        }

        private static IType UnwrapType(IType type, IPsiModule module)
        {
            for (var depth = 0; depth < MaxUnwrap; depth++)
            {
                var declared = type.Unlift() as IDeclaredType;
                if (declared == null || declared.IsString())
                    return type;

                var values = CollectionTypeUtil.GetElementTypesForGenericType(
                    declared, module.GetPredefinedType().GenericIDictionary, 1);
                if (values != null && values.Count > 0)
                {
                    type = values[0];
                    continue;
                }

                var elements = CollectionTypeUtil.GetElementTypesForGenericEnumerable(declared, false);
                if (elements.Count > 0)
                {
                    type = elements[0];
                    continue;
                }

                return declared;
            }

            return type;
        }

        private static bool IsDictionary(IType type, IPsiModule module)
        {
            var declared = type.Unlift() as IDeclaredType;
            if (declared == null)
                return false;

            var values = CollectionTypeUtil.GetElementTypesForGenericType(
                declared, module.GetPredefinedType().GenericIDictionary, 1);
            return values != null && values.Count > 0;
        }

        private static bool IsSequence(IType type, IPsiModule module)
        {
            var declared = type.Unlift() as IDeclaredType;
            if (declared == null || declared.IsString() || IsDictionary(type, module))
                return false;

            return CollectionTypeUtil.GetElementTypesForGenericEnumerable(declared, false).Count > 0;
        }

        private static Values EnumValues(IType type, IPsiModule module)
        {
            var result = new Values
            {
                Key = new List<string>(),
                Value = Members(type) ?? Members(UnwrapType(type, module)) ?? new List<string>(),
            };

            var declared = type.Unlift() as IDeclaredType;
            if (declared != null)
            {
                var keys = CollectionTypeUtil.GetElementTypesForGenericType(
                    declared, module.GetPredefinedType().GenericIDictionary, 0);
                if (keys != null && keys.Count > 0)
                    result.Key = Members(keys[0]) ?? result.Key;
            }

            return result;
        }

        private static List<string> Members(IType type)
        {
            var enumeration = (type.Unlift() as IDeclaredType)?.GetTypeElement() as IEnum;
            return enumeration?.EnumMembers
                .Where(it => it.IsEnumMember)
                .Select(it => it.ShortName)
                .ToList();
        }

        private static ITypeElement Unwrap(IType type, IPsiModule module)
        {
            var unwrapped = UnwrapType(type, module) as IDeclaredType;
            return unwrapped == null || unwrapped.IsString() ? null : unwrapped.GetTypeElement();
        }

        private static Kinds PrototypeKinds(IType type, IAttribute attribute, IPsiModule module)
        {
            var serializer = SerializerArgument(attribute) as IDeclaredType;
            if (serializer != null)
            {
                var kind = KindOfPrototype(LastTypeArgument(serializer));
                var name = serializer.GetTypeElement()?.ShortName ?? "";
                return name.EndsWith(DictionarySerializer) && !name.EndsWith(ValueDictionarySerializer)
                    ? new Kinds { Key = kind }
                    : new Kinds { Value = kind };
            }

            var result = new Kinds { Value = KindOfType(UnwrapType(type, module)) };

            var declared = type as IDeclaredType;
            if (declared != null)
            {
                var keys = CollectionTypeUtil.GetElementTypesForGenericType(
                    declared, module.GetPredefinedType().GenericIDictionary, 0);
                if (keys != null && keys.Count > 0)
                    result.Key = KindOfType(keys[0]);
            }

            return result;
        }

        private static string KindOfType(IType type)
        {
            var declared = type.Unlift() as IDeclaredType;
            var element = declared?.GetTypeElement();
            if (element == null)
                return null;

            if (element.ShortName == EntProtoId)
                return EntityKind;

            if (element.ShortName == ProtoId)
                return KindOfPrototype(LastTypeArgument(declared));

            return KindOf(element);
        }

        private static string KindOfPrototype(IType type)
        {
            var element = (type as IDeclaredType)?.GetTypeElement();
            return element == null ? null : KindOf(element);
        }

        private static bool IsRecordField(ITypeMember member) =>
            !member.IsStatic
            && member.GetAccessRights() == AccessRights.PUBLIC
            && (member is IProperty || member is IField);

        private static bool HasAttribute(ITypeElement type, string name) =>
            FindAttribute(type.GetDeclarations(), it => it == name) != null
            || type.GetAttributeInstances(AttributesSource.Self)
                .Any(it => WithoutSuffix(it.GetAttributeShortName()) == name);

        private static IType SerializerArgument(IAttribute attribute)
        {
            if (attribute == null)
                return null;

            foreach (var argument in attribute.Arguments)
            {
                var type = (argument.Value as ITypeofExpression)?.ArgumentType;
                if (type != null)
                    return type;
            }

            return null;
        }

        private static string FixedName(IAttribute attribute)
        {
            if (attribute == null)
                return null;

            string name;
            return FixedNames.TryGetValue(WithoutSuffix(attribute.Name?.ShortName) ?? "", out name) ? name : null;
        }

        private static IType LastTypeArgument(IDeclaredType type)
        {
            var element = type.GetTypeElement();
            if (element == null || element.TypeParametersCount == 0)
                return null;

            return type.GetSubstitution().Apply(element.TypeParameters[element.TypeParametersCount - 1]);
        }

        private static string KindOf(ITypeElement prototype)
        {
            var attribute = FindAttribute(prototype.GetDeclarations(), it => it == PrototypeAttribute);
            if (attribute != null)
                return ExplicitName(attribute) ?? KindFromName(prototype.ShortName);

            var instance = prototype.GetAttributeInstances(AttributesSource.Self)
                .FirstOrDefault(it => WithoutSuffix(it.GetAttributeShortName()) == PrototypeAttribute);
            if (instance == null)
                return null;

            return MetadataName(instance) ?? KindFromName(prototype.ShortName);
        }

        private static string MetadataName(IAttributeInstance instance)
        {
            try
            {
                foreach (var value in instance.PositionParameters())
                {
                    if (value.IsConstant && value.ConstantValue.StringValue != null)
                        return value.ConstantValue.StringValue;
                }
            }
            catch (Exception)
            {
                return null;
            }

            return null;
        }

        private static string KindFromName(string name) =>
            name.EndsWith(PrototypeAttribute)
                ? Decapitalize(name.Substring(0, name.Length - PrototypeAttribute.Length))
                : null;

        private static string ExplicitName(IAttribute attribute)
        {
            if (attribute == null)
                return null;

            var argument = attribute.Arguments.FirstOrDefault();
            if (argument == null || argument.NameIdentifier != null)
                return null;

            var text = (argument.Value as ICSharpLiteralExpression)?.Literal?.GetText();
            if (text == null || text.Length < 2 || text[0] != '"' || text[text.Length - 1] != '"')
                return null;

            return text.Substring(1, text.Length - 2);
        }

        private static IAttribute DataFieldAttribute(ITypeMember member) =>
            FindAttribute(member.GetDeclarations(), it => it != null && DataFieldAttributes.Contains(it));

        private static IAttribute FindAttribute(IEnumerable<IDeclaration> declarations, Func<string, bool> match)
        {
            foreach (var declaration in declarations.OfType<IAttributesOwnerDeclaration>())
            {
                foreach (var attribute in declaration.Attributes)
                {
                    if (match(WithoutSuffix(attribute.Name?.ShortName)))
                        return attribute;
                }
            }

            return null;
        }

        private const int MaxTypes = 32;
        private const int MaxUnwrap = 4;
        private const string IncludeDataField = "IncludeDataField";
        private const string DataRecordAttribute = "DataRecord";
        private const string Unresolved = "???";
        private const string PrototypeAttribute = "Prototype";
        private const string ProtoId = "ProtoId";
        private const string EntProtoId = "EntProtoId";
        private const string EntityKind = "entity";
        private const string DictionarySerializer = "DictionarySerializer";
        private const string ValueDictionarySerializer = "ValueDictionarySerializer";

        private static readonly Dictionary<string, string> FixedNames =
            new Dictionary<string, string>(StringComparer.Ordinal)
            {
                { "IdDataField", "id" },
                { "ParentDataField", "parent" },
                { "AbstractDataField", "abstract" },
            };

        private static readonly HashSet<string> DataFieldAttributes = new HashSet<string>
        {
            "DataField",
            "IdDataField",
            "ParentDataField",
            "AbstractDataField",
            IncludeDataField,
        };

        private static string WithoutSuffix(string name) =>
            name != null && name.EndsWith("Attribute") ? name.Substring(0, name.Length - "Attribute".Length) : name;

        private static string Decapitalize(string name) =>
            name.Length == 0 ? name : char.ToLowerInvariant(name[0]) + name.Substring(1);
    }
}
#endif
