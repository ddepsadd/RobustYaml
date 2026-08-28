#if RIDER
using System;
using System.Collections.Generic;
using System.Linq;
using JetBrains.Application.Parts;
using JetBrains.Application.Progress;
using JetBrains.Metadata.Reader.API;
using JetBrains.ProjectModel;
using JetBrains.Rd.Tasks;
using JetBrains.ReSharper.Feature.Services.Protocol;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.Caches;
using JetBrains.ReSharper.Psi.CSharp.Tree;
using JetBrains.ReSharper.Psi.Modules;
using JetBrains.ReSharper.Psi.Resolve;
using JetBrains.ReSharper.Psi.Search;
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

        public RobustYamlHost(ISolution solution)
        {
            mySolution = solution;
            var model = solution.GetProtocolSolution().GetRobustYamlModel();
            model.TypeFields.SetSync((_, query) => Fields(query.ClassName, query.Path));
            model.TypeImplementations.SetSync((_, query) => Implementations(query.ClassName, query.Path));
        }

        private sealed class Field
        {
            public string Name;
            public IType Type;
            public ITypeMember Member;
            public string PrototypeKind;
            public string KeyPrototypeKind;
            public bool CustomSerializer;
            public List<string> Constants;
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

        /// <summary>
        /// Everything here is guarded, and the guard is not caution but arithmetic. Building a type
        /// in an implicit universal context throws — <c>CSharpTypeFactory.CreateType</c> with an NRE,
        /// <c>GetRuntimeFeatures</c> with an assert — and the branch that gets there is reachable:
        /// a module without a containing project falls back to the explicit universal context, which
        /// silences the assert without making the types build. A throw is answered by the frontend
        /// with a warning and a dropped cache entry, so the same type is asked again on the next pass
        /// of the daemon, and the next, each time paying for the whole symbol lookup. Answering
        /// <see cref="Unbuilt"/> instead says the same thing — "I could not" — for the price of one
        /// call. Cancellation is not an answer and is left to propagate.
        /// </summary>
        private RobustFieldsReply Fields(string className, List<string> path)
        {
            try
            {
                return FieldsUnguarded(className, path);
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception)
            {
                return Unbuilt;
            }
        }

        private RobustFieldsReply FieldsUnguarded(string className, List<string> path)
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

                    var members = Collect(type, out var walked);
                    if (!walked)
                        return Unbuilt;

                    var step = members.FirstOrDefault(it => it.Name == segment);
                    if (step == null)
                        return Resolved(result);

                    if (step.Type.GetPresentableName(type.PresentationLanguage).Contains(Unresolved))
                        return Unbuilt;

                    keySegment = IsDictionary(step.Type, type.Module);
                    type = Unwrap(step.Type, type.Module);
                    if (type == null)
                        return Resolved(result);
                }

                var fields = Collect(type, out var complete);
                if (!complete)
                    return Unbuilt;

                foreach (var field in fields)
                {
                    var presentable = field.Type.GetPresentableName(type.PresentationLanguage);
                    if (presentable.Contains(Unresolved))
                        return Unbuilt;

                    // A name that says ProtoId while no kind came out is the same half-answer as a
                    // type that did not build: twelve fields come back, everything reads as
                    // successful, and only the kind is quietly missing. Cached, that kills the id
                    // completion and the typed check for the whole session — `[categories=
                    // entityCategory, parent=entity]` on one run, `[parent=entity]` on the next,
                    // from unchanged sources. The judgement is made on the presentable name because
                    // it is the one thing here known to be resolved: the failure happens inside
                    // UnwrapType, where a cold cache makes GetElementTypesForGenericEnumerable
                    // answer with an empty list, which is indistinguishable from "not a collection",
                    // so the collection itself is returned and its short name is HashSet, not
                    // ProtoId. Every type named by a ProtoId in the content carries [Prototype]
                    // — all 191 of them — so a missing kind is never the honest answer.
                    if (presentable.Contains(ProtoId) &&
                        field.PrototypeKind == null && field.KeyPrototypeKind == null)
                        return Unbuilt;

                    var values = EnumValues(field.Type, type.Module);
                    if (field.Constants != null)
                        values.Value = field.Constants;

                    result.Add(new RobustDataField(
                        field.Name,
                        presentable,
                        Summary(field.Member),
                        DefaultValue(field.Member),
                        field.PrototypeKind,
                        field.KeyPrototypeKind,
                        IsDictionary(field.Type, type.Module),
                        IsSequence(field.Type, type.Module),
                        field.CustomSerializer,
                        IsLocalized(field.Type, type.Module),
                        IsPolymorphic(field.Type, type.Module),
                        values.Value,
                        values.Key));
                }
            }

            return Resolved(result);
        }

        /// <summary>
        /// Types a `!type:` tag may name at the end of <paramref name="path"/>. The declared type of
        /// the field is usually abstract — `IPhysShape`, `IThresholdBehavior` — and the tag picks the
        /// class that is actually read, so the answer is its inheritors plus itself when it can be
        /// instantiated.
        /// </summary>
        private RobustImplementationsReply Implementations(string className, List<string> path)
        {
            try
            {
                return ImplementationsUnguarded(className, path);
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception)
            {
                return UnbuiltImplementations;
            }
        }

        private RobustImplementationsReply ImplementationsUnguarded(string className, List<string> path)
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
                    var reply = ImplementationsOf(candidate, services, path);
                    if (!reply.Resolved || reply.Names.Count > 0)
                        return reply;
                }

                return new RobustImplementationsReply(candidates.Count > 0, new List<string>());
            }
        }

        private RobustImplementationsReply ImplementationsOf(
            ITypeElement found, IPsiServices services, List<string> path)
        {
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

                    var members = Collect(type, out var walked);
                    if (!walked)
                        return UnbuiltImplementations;

                    var step = members.FirstOrDefault(it => it.Name == segment);
                    if (step == null)
                        return NoImplementations;

                    if (step.Type.GetPresentableName(type.PresentationLanguage).Contains(Unresolved))
                        return UnbuiltImplementations;

                    keySegment = IsDictionary(step.Type, type.Module);
                    type = Unwrap(step.Type, type.Module);
                    if (type == null)
                        return NoImplementations;
                }

                // A set, because what is collected are short names and those collide: `EntityPrototype`
                // is declared four times in the content — the prototype, a nested type in
                // `SpriteSpecifier` and two in the engine's own tests. Validation would not notice,
                // it only asks whether a name is in the list; completion would show the same row
                // twice with nothing to choose between. Sorted on the way out so that the order of
                // the list does not depend on the order the search happened to walk the solution in.
                var names = new HashSet<string>(StringComparer.Ordinal);
                if (Instantiable(type))
                    names.Add(type.ShortName);

                var domain = SearchDomainFactory.Instance.CreateSearchDomain(mySolution, false);
                services.Finder.FindInheritors(
                    TypeFactory.CreateType(type),
                    inheritor =>
                    {
                        var element = inheritor.GetTypeElement();
                        if (element != null && Instantiable(element))
                            names.Add(element.ShortName);
                        return names.Count < InheritorLimit ? FindExecution.Continue : FindExecution.Stop;
                    },
                    domain,
                    NullProgressIndicator.Create());

                // A truncated list would turn a valid tag into an error, so it is reported as
                // "nothing is known here" instead: validation stays silent and completion offers nothing.
                if (names.Count >= InheritorLimit)
                    return NoImplementations;

                return new RobustImplementationsReply(
                    true, names.OrderBy(it => it, StringComparer.Ordinal).ToList());
            }
        }

        private static RobustImplementationsReply NoImplementations =>
            new RobustImplementationsReply(true, new List<string>());

        private static RobustImplementationsReply UnbuiltImplementations =>
            new RobustImplementationsReply(false, new List<string>());

        private static bool Instantiable(ITypeElement type)
        {
            var klass = type as IClass;
            if (klass != null)
                return !klass.IsAbstract;
            return type is IStruct;
        }

        private const int InheritorLimit = 500;

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

        /// <summary>
        /// What the field is worth when the prototype says nothing about it. The initializer is
        /// syntax, not a constant: computing it would need a concrete module, and the same assert
        /// that forbids reading an attribute argument as a constant applies here.
        ///
        /// Two forms are dropped rather than shown. <c>default!</c> and <c>default(T)</c> say only
        /// that the author had nothing to put there — 763 datafields of ss14-wega are written that
        /// way — and a parameterless <c>new()</c> says only "empty", which the type already said
        /// (683 more). An object creation carrying arguments or an initializer body stays: the
        /// 491 remaining ones are `new SoundPathSpecifier("/Audio/…")` and the like, where the value
        /// is the answer. The rest — 1812 numbers, 1028 strings, 824 booleans, 751 `Color.Orange`
        /// and its kin — is exactly what the hover is asked for.
        /// </summary>
        private static string DefaultValue(ITypeMember member)
        {
            foreach (var declaration in member.GetDeclarations())
            {
                IVariableInitializer initial = null;
                if (declaration is IFieldDeclaration field)
                    initial = field.Initial;
                else if (declaration is IPropertyDeclaration property)
                    initial = property.Initial;

                var value = (initial as IExpressionInitializer)?.Value;
                while (value is ISuppressNullableWarningExpression suppressed)
                    value = suppressed.Operand as ICSharpExpression;

                if (value == null || !Informative(value))
                    continue;

                var text = Collapse(value.GetText());
                if (!string.IsNullOrEmpty(text))
                    return text;
            }

            return null;
        }

        private static bool Informative(ICSharpExpression value)
        {
            if (value is IDefaultExpression)
                return false;
            if (value is IObjectCreationExpression creation)
                return creation.Arguments.Count > 0 || creation.Initializer != null;
            return true;
        }

        /// <summary>
        /// One line out of what may be several: an initializer body is written across lines, and the
        /// hover has a single row for it. Long ones are cut rather than wrapped — the point of the
        /// row is the value, and what does not fit is read in the source.
        /// </summary>
        private static string Collapse(string text)
        {
            if (string.IsNullOrWhiteSpace(text))
                return null;

            var builder = new System.Text.StringBuilder(text.Length);
            var space = false;
            foreach (var symbol in text)
            {
                if (char.IsWhiteSpace(symbol))
                {
                    space = builder.Length > 0;
                    continue;
                }

                if (space)
                    builder.Append(' ');
                space = false;
                builder.Append(symbol);
            }

            if (builder.Length <= DefaultLimit)
                return builder.ToString();

            // Length counts UTF-16 units, not characters: anything outside the basic plane takes two
            // of them, and cutting at exactly the limit can land between the halves. What would be
            // shipped then is a lone surrogate — not a character in any sense, and drawn as garbage.
            // The default value of a field is arbitrary text out of somebody's code, so "it is all
            // ASCII anyway" is not an assumption to make.
            var cut = DefaultLimit;
            if (char.IsHighSurrogate(builder[cut - 1]))
                cut--;

            return builder.ToString(0, cut) + "…";
        }

        private const int DefaultLimit = 80;

        private IModuleReferenceResolveContext ResolveContext(IPsiModule module)
        {
            var containing = module.ContainingProjectModule;
            if (containing == null)
                return null;

            return mySolution.GetComponent<PsiModuleResolveContextManager>()
                .GetOrCreateModuleResolveContext(containing, module, module.TargetFrameworkId);
        }

        /// <summary>
        /// <paramref name="complete"/> is false when the walk stopped at <see cref="MaxTypes"/> with
        /// types still queued. It has to travel with the list, because a truncated list is not a
        /// smaller answer but a different one: the frontend keeps a resolved reply in <c>ready</c>
        /// until a `.cs` changes, so fields that were never reached would be missing for the rest of
        /// the session — keys that exist would be painted as unknown, and no amount of re-asking
        /// would fix it, since nothing would be asked again. The same rule already governs
        /// <see cref="InheritorLimit"/>: a limit reports "nothing is known here", never a result.
        /// Measured on ss14-wega, the widest hierarchy reaches 10 types of the 32 allowed.
        /// </summary>
        private List<Field> Collect(ITypeElement root, out bool complete)
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
                        CustomSerializer = SerializerArgument(attribute) != null,
                        Constants = ConstantValues(attribute, type) ?? FlagValues(attribute),
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

            complete = queue.Count == 0;
            return result;
        }

        private static IType UnwrapType(IType type, IPsiModule module)
        {
            for (var depth = 0; depth < MaxUnwrap; depth++)
            {
                var array = type.Unlift() as IArrayType;
                if (array != null)
                {
                    type = array.ElementType;
                    continue;
                }

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

        /// <summary>
        /// Whether the value of this field is chosen by a `!type:` tag: the declared type cannot be
        /// instantiated, so the concrete class has to be named in YAML. Asking for inheritors is a
        /// solution-wide search, and this flag is what keeps the frontend from asking about every
        /// `float` field it meets.
        /// </summary>
        private static bool IsPolymorphic(IType type, IPsiModule module)
        {
            var element = Unwrap(type, module);
            if (element == null)
                return false;

            if (element is IInterface)
                return true;

            var klass = element as IClass;
            return klass != null && klass.IsAbstract;
        }

        private static bool IsSequence(IType type, IPsiModule module)
        {
            // An array is an IArrayType, never an IDeclaredType, so the cast below drops it and
            // `vertices: Vector2[]` would be written as a scalar key.
            if (type.Unlift() is IArrayType)
                return true;

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

            // Unlift first, as everywhere else here: `X?` over a value type is a real Nullable<>
            // wrapper, and reading through it is what once cost the Prototype section of the hover
            // on 326 declarations of `EntProtoId?`. A dictionary is a class, so today the cast would
            // survive without it — which is exactly how the habit gets lost.
            var declared = type.Unlift() as IDeclaredType;
            if (declared != null)
            {
                var keys = CollectionTypeUtil.GetElementTypesForGenericType(
                    declared, module.GetPredefinedType().GenericIDictionary, 0);
                if (keys != null && keys.Count > 0)
                    result.Key = KindOfType(keys[0]);
            }

            return result;
        }

        /// <summary>
        /// Values a <c>ConstantSerializer&lt;TTag&gt;</c> accepts: the members of the enum marked
        /// <c>[ConstantsFor(typeof(TTag))]</c>. The tag itself carries no members, so the enum has to
        /// be looked up, and the only handle on it is its short name — which the engine and the
        /// content spell the same (<c>DrawDepth</c>). The search runs in the full symbol scope, not
        /// in the owner's module: <c>drawdepth</c> is declared in <c>Robust.Client</c>, the enum
        /// lives in <c>Content.Shared</c>, and the engine does not reference the content. The
        /// attribute has to be there, and its argument is compared when it resolves.
        /// </summary>
        private static List<string> ConstantValues(IAttribute attribute, ITypeElement owner)
        {
            var serializer = SerializerArgument(attribute) as IDeclaredType;
            if (serializer?.GetTypeElement()?.ShortName != ConstantSerializer)
                return null;

            var tag = (LastTypeArgument(serializer) as IDeclaredType)?.GetTypeElement();
            if (tag == null)
                return null;

            List<IEnum> candidates;
            using (CompilationContextCookie.GetExplicitUniversalContextIfNotSet())
            {
                var scope = owner.GetPsiServices().Symbols
                    .GetSymbolScope(LibrarySymbolScope.FULL, caseSensitive: true);
                candidates = scope.GetElementsByShortName(tag.ShortName).OfType<IEnum>().ToList();
            }

            foreach (var candidate in candidates)
            {
                var marker = FindAttribute(candidate.GetDeclarations(), it => it == ConstantsForAttribute);
                if (marker == null && !HasAttribute(candidate, ConstantsForAttribute))
                    continue;

                var argument = (SerializerArgument(marker) as IDeclaredType)?.GetTypeElement();
                if (argument != null && !Equals(argument.GetClrName(), tag.GetClrName()))
                    continue;

                var members = candidate.EnumMembers
                    .Where(it => it.IsEnumMember)
                    .Select(it => it.ShortName)
                    .ToList();
                if (members.Count > 0)
                    return members;
            }

            return null;
        }

        /// <summary>
        /// Values a <c>FlagSerializer&lt;TTag&gt;</c> accepts: the members of the enum marked
        /// <c>[FlagsFor(typeof(TTag))]</c>, which is how <c>SerializationManager</c> builds its own
        /// mapping — one enum per tag, or it throws.
        ///
        /// <para>
        /// The name trick that finds the enum of a <c>ConstantSerializer</c> is useless here: of the
        /// three tags in the content only <c>AtmosDirectionFlags</c> resembles its enum
        /// (<c>AtmosDirection</c>), while <c>CollisionLayer</c> and <c>CollisionMask</c> both map to
        /// <c>CollisionGroup</c> and <c>VisibilityMaskLayer</c> to <c>VisibilityFlags</c>. So the tag
        /// is searched for instead: it is referenced by the attribute that names it and by the
        /// handful of fields serialized with it — three places for the widest of them. The domain is
        /// the whole solution on purpose, for the same reason the constants search runs in the full
        /// symbol scope: the tags live in <c>Robust.Shared</c>, the enums in <c>Content.Shared</c>,
        /// and the engine does not reference the content.
        /// </para>
        ///
        /// <para>
        /// An enum that came from a compiled assembly carries no declaration to find, so nothing is
        /// returned and the field keeps an empty value list — which reads as "nothing is known here",
        /// leaving validation silent instead of wrong.
        /// </para>
        /// </summary>
        private List<string> FlagValues(IAttribute attribute)
        {
            var serializer = SerializerArgument(attribute) as IDeclaredType;
            if (serializer?.GetTypeElement()?.ShortName != FlagSerializer)
                return null;

            var tag = (LastTypeArgument(serializer) as IDeclaredType)?.GetTypeElement();
            if (tag == null)
                return null;

            var domain = SearchDomainFactory.Instance.CreateSearchDomain(mySolution, false);
            var references = tag.GetPsiServices().Finder
                .FindReferences(tag, domain, NullProgressIndicator.Create());

            foreach (var reference in references)
            {
                var marker = reference.GetTreeNode()?.GetContainingNode<IAttribute>();
                if (marker == null || WithoutSuffix(marker.Name?.ShortName) != FlagsForAttribute)
                    continue;

                var declaration = marker.GetContainingNode<IAttributesOwnerDeclaration>();
                var enumeration = declaration?.DeclaredElement as IEnum;
                if (enumeration == null)
                    continue;

                var members = enumeration.EnumMembers
                    .Where(it => it.IsEnumMember)
                    .Select(it => it.ShortName)
                    .ToList();
                if (members.Count > 0)
                    return members;
            }

            return null;
        }

        private static bool IsLocalized(IType type, IPsiModule module)
        {
            var declared = UnwrapType(type, module).Unlift() as IDeclaredType;
            return declared?.GetTypeElement()?.ShortName == LocId;
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
        private const string LocId = "LocId";
        private const string ConstantSerializer = "ConstantSerializer";
        private const string ConstantsForAttribute = "ConstantsFor";
        private const string FlagSerializer = "FlagSerializer";
        private const string FlagsForAttribute = "FlagsFor";
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
