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
        }

        private List<RobustDataField> Fields(string className, List<string> path)
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
                    var fields = FieldsOf(candidate, services, path);
                    if (fields.Count > 0)
                        return fields;
                }
            }

            return new List<RobustDataField>();
        }

        private List<RobustDataField> FieldsOf(ITypeElement found, IPsiServices services, List<string> path)
        {
            var result = new List<RobustDataField>();

            var context = ResolveContext(found.Module);
            using (context == null
                       ? CompilationContextCookie.GetExplicitUniversalContextIfNotSet()
                       : CompilationContextCookie.GetOrCreate(context))
            {
                var scope = services.Symbols.GetSymbolScope(found.Module, true, true);
                var type = scope.GetTypeElementByCLRName(found.GetClrName()) ?? found;

                foreach (var segment in path)
                {
                    var step = Collect(type).FirstOrDefault(it => it.Name == segment);
                    if (step == null)
                        return result;

                    type = Unwrap(step.Type, type.Module);
                    if (type == null)
                        return result;
                }

                foreach (var field in Collect(type))
                    result.Add(new RobustDataField(
                        field.Name,
                        field.Type.GetPresentableName(type.PresentationLanguage),
                        field.Member.GetXMLDoc(true)?.Value,
                        field.PrototypeKind));
            }

            return result;
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

                foreach (var member in type.GetMembers())
                {
                    if (!(member is ITypeOwner owner))
                        continue;

                    var attribute = DataFieldAttribute(member);
                    if (attribute == null)
                        continue;

                    var memberType = substitution.Apply(owner.Type);

                    if (WithoutSuffix(attribute.Name?.ShortName) == IncludeDataField)
                    {
                        var included = memberType as IDeclaredType;
                        var element = included?.GetTypeElement();
                        if (element != null && visited.Add(element.GetClrName().FullName))
                            queue.Enqueue(new KeyValuePair<ITypeElement, ISubstitution>(
                                element, included.GetSubstitution()));
                        continue;
                    }

                    var name = ExplicitName(attribute) ?? Decapitalize(member.ShortName.TrimStart('_'));
                    if (!seen.Add(name))
                        continue;

                    result.Add(new Field
                    {
                        Name = name,
                        Type = memberType,
                        Member = member,
                        PrototypeKind = PrototypeKind(memberType, attribute, type.Module),
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
                var declared = type as IDeclaredType;
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

        private static ITypeElement Unwrap(IType type, IPsiModule module)
        {
            var unwrapped = UnwrapType(type, module) as IDeclaredType;
            return unwrapped == null || unwrapped.IsString() ? null : unwrapped.GetTypeElement();
        }

        private static string PrototypeKind(IType type, IAttribute attribute, IPsiModule module)
        {
            var target = SerializerArgument(attribute) ?? UnwrapType(type, module);
            var declared = target as IDeclaredType;
            var element = declared?.GetTypeElement();
            if (element == null)
                return null;

            if (element.ShortName == EntProtoId)
                return EntityKind;

            if (element.ShortName == ProtoId || element.ShortName.EndsWith("Serializer"))
            {
                declared = FirstTypeArgument(declared) as IDeclaredType;
                element = declared?.GetTypeElement();
                if (element == null)
                    return null;
            }

            return KindOf(element);
        }

        private static IType SerializerArgument(IAttribute attribute)
        {
            foreach (var argument in attribute.Arguments)
            {
                if (argument.NameIdentifier?.Name != "customTypeSerializer")
                    continue;

                return (argument.Value as ITypeofExpression)?.ArgumentType;
            }

            return null;
        }

        private static IType FirstTypeArgument(IDeclaredType type)
        {
            var element = type.GetTypeElement();
            if (element == null || element.TypeParametersCount == 0)
                return null;

            return type.GetSubstitution().Apply(element.TypeParameters[0]);
        }

        private static string KindOf(ITypeElement prototype)
        {
            var declaration = prototype.GetDeclarations().OfType<IAttributesOwnerDeclaration>().FirstOrDefault();
            var attribute = declaration?.Attributes
                .FirstOrDefault(it => WithoutSuffix(it.Name?.ShortName) == PrototypeAttribute);
            if (attribute == null)
                return null;

            var explicitKind = ExplicitName(attribute);
            if (explicitKind != null)
                return explicitKind;

            var name = prototype.ShortName;
            return name.EndsWith(PrototypeAttribute)
                ? Decapitalize(name.Substring(0, name.Length - PrototypeAttribute.Length))
                : null;
        }

        private static string ExplicitName(IAttribute attribute)
        {
            var argument = attribute.Arguments.FirstOrDefault();
            if (argument == null || argument.NameIdentifier != null)
                return null;

            var text = (argument.Value as ICSharpLiteralExpression)?.Literal?.GetText();
            if (text == null || text.Length < 2 || text[0] != '"' || text[text.Length - 1] != '"')
                return null;

            return text.Substring(1, text.Length - 2);
        }

        private static IAttribute DataFieldAttribute(ITypeMember member)
        {
            var declaration = member.GetDeclarations()
                .OfType<IAttributesOwnerDeclaration>()
                .FirstOrDefault();
            return declaration?.Attributes
                .FirstOrDefault(it => DataFieldAttributes.Contains(WithoutSuffix(it.Name?.ShortName)));
        }

        private const int MaxTypes = 32;
        private const int MaxUnwrap = 4;
        private const string IncludeDataField = "IncludeDataField";
        private const string PrototypeAttribute = "Prototype";
        private const string ProtoId = "ProtoId";
        private const string EntProtoId = "EntProtoId";
        private const string EntityKind = "entity";

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
