/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.vm.hosted;

import static com.sun.max.vm.VMConfiguration.*;
import static com.sun.max.vm.compiler.CallEntryPoint.*;
import static com.sun.max.vm.type.ClassRegistry.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import com.sun.max.annotate.*;
import com.sun.max.collect.*;
import com.sun.max.lang.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.MaxineVM.Phase;
import com.sun.max.vm.actor.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.code.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.debug.*;
import com.sun.max.vm.hosted.CompiledPrototype.Link.Relationship;
import com.sun.max.vm.jdk.*;
import com.sun.max.vm.jni.*;
import com.sun.max.vm.run.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.type.*;

/**
 * The bootstrapping phase responsible for compiling and linking methods in the target.
 *
 * @author Bernd Mathiske
 * @author Doug Simon
 * @author Ben L. Titzer
 */
public class CompiledPrototype extends Prototype {

    private class ClassInfo {
        private final HashSet<MethodActor> indirectCalls = new HashSet<MethodActor>();
        private final HashSet<ClassActor> subClasses = new HashSet<ClassActor>();
        private final HashSet<ClassActor> implementors = new HashSet<ClassActor>();
    }

    private final HashMap<ClassActor, ClassInfo> classActorInfo = new HashMap<ClassActor, ClassInfo>();
    private final HashMap<MethodActor, Set<ClassActor>> anonymousClasses = new HashMap<MethodActor, Set<ClassActor>>();

    private static final HashMap<MethodActor, RuntimeCompilerScheme> recommendedCompiler = new HashMap<MethodActor, RuntimeCompilerScheme>();

    private final HashMap<MethodActor, Link> methodActors = new HashMap<MethodActor, Link>();
    private final LinkedList<MethodActor> worklist = new LinkedList<MethodActor>();

    private static RuntimeCompilerScheme c1xCompiler;

    /**
     * The link from a <i>referrer</i> method to a <i>referent</i> method where the referrer caused the referent to be
     * compiled in the image.
     */
    public static class Link {

        enum Relationship {
            DIRECT_CALL("directly calls", "is directly called by"),
            VIRTUAL_CALL("virtually calls", "is virtually called by"),
            INTERFACE_CALL("interfacially calls", "is interfacially called by"),
            LITERAL("has a literal reference to", "is referenced as a literal by"),
            IMPLEMENTS("is implemented by", "implements"),
            OVERRIDES("is overridden by", "overrides");

            final String asReferrer;
            final String asReferent;

            private Relationship(String asReferrer, String asReferent) {
                this.asReferrer = asReferrer;
                this.asReferent = asReferent;
            }
        }
        final MethodActor referent;
        final MethodActor referrer;
        final Relationship relationship;

        public Link(MethodActor referent, MethodActor referrer, Relationship relationship) {
            assert referent != null;
            //assert referent != referrer;
            this.referent = referent;
            this.referrer = referrer;
            this.relationship = relationship;
        }

        private static String name(MethodActor methodActor) {
            if (methodActor == null) {
                return null;
            }
            return methodActor.format("%H.%n(%p)") + ":" + methodActor.descriptor().resultDescriptor().toJavaString(false);
        }

        public String referrerName() {
            return name(referrer);
        }

        public String referentName() {
            return name(referent);
        }

        public MethodActor referent() {
            return referent;
        }

        public MethodActor referrer() {
            return referrer;
        }

        public Relationship relationship() {
            return relationship;
        }

        @Override
        public String toString() {
            if (referrer == null) {
                return referentName() + " is a VM entry point";
            }
            return referrerName() + " " + relationship.asReferrer + " " + referentName();
        }
    }

    public Collection<Link> links() {
        return methodActors.values();
    }

    public RuntimeCompilerScheme jitScheme() {
        return vmConfig().jitCompilerScheme();
    }

    private ClassInfo lookupInfo(ClassActor classActor) {
        return classActorInfo.get(classActor);
    }

    private ClassInfo getInfo(ClassActor classActor) {
        ClassInfo info = classActorInfo.get(classActor);
        if (info == null) {
            info = new ClassInfo();
            classActorInfo.put(classActor, info);
        }
        return info;
    }

    private Set<ClassActor> getAnonymousClasses(MethodActor actor) {
        Set<ClassActor> anonymousClasses = this.anonymousClasses.get(actor);
        if (anonymousClasses == null) {
            anonymousClasses = new HashSet<ClassActor>();
            this.anonymousClasses.put(actor, anonymousClasses);
        }
        return anonymousClasses;
    }

    private Set<ClassActor> lookupAnonymousClasses(MethodActor actor) {
        return anonymousClasses.get(actor);
    }

    private void gatherNewClasses() {
        Trace.begin(1, "gatherNewClasses");
        final LinkedList<ClassActor> newClasses = new LinkedList<ClassActor>();
        for (ClassActor classActor : ClassRegistry.BOOT_CLASS_REGISTRY.copyOfClasses()) {
            if (lookupInfo(classActor) == null) {
                final Method enclosingMethod = classActor.toJava().getEnclosingMethod();
                if (enclosingMethod != null) {
                    // if this is an anonymous class, add it to the anonymous classes set of the enclosing method
                    gatherNewAnonymousClass(newClasses, classActor, enclosingMethod);
                } else {
                    traceNewClass(classActor);
                    newClasses.add(classActor);
                }
            }
        }
        Trace.end(1, "gatherNewClasses");
        Trace.begin(1, "processNewClasses " + newClasses.size());
        for (ClassActor classActor : newClasses) {
            processNewClass(classActor);
        }
        Trace.end(1, "processNewClasses");
    }

    private void traceNewClass(ClassActor classActor) {
        if (Trace.hasLevel(2)) {
            Trace.line(2, "new class: " + classActor);
        }
    }

    private void gatherNewAnonymousClass(final LinkedList<ClassActor> newClasses, ClassActor classActor, final Method enclosingMethod) {
        if (!MaxineVM.isHostedOnly(enclosingMethod)) {
            final MethodActor methodActor = MethodActor.fromJava(enclosingMethod);
            if (methodActor != null) {
                getAnonymousClasses(methodActor).add(classActor);
                if (methodActors.containsKey(methodActor)) {
                    traceNewClass(classActor);
                    newClasses.add(classActor);
                }
            }
        }
    }

    private void processNewClass(ClassActor classActor) {
        getInfo(classActor); // build the class info for this class
        ClassActor superClassActor = classActor.superClassActor;
        while (superClassActor != null) {
            // for each super class of this class, add this class's implementation of its methods used so far
            final ClassInfo superInfo = getInfo(superClassActor);
            superInfo.subClasses.add(classActor);
            for (MethodActor methodActor : superInfo.indirectCalls) {
                add(classActor.findVirtualMethodActor(methodActor), methodActor, Relationship.OVERRIDES);
            }
            superClassActor = superClassActor.superClassActor;
        }
        if (!classActor.isInterface()) {
            // for each interface that this class implements, add this class's implementation of its methods used so far
            for (InterfaceActor interfaceActor : classActor.getAllInterfaceActors()) {
                final ClassInfo interfaceInfo = getInfo(interfaceActor);
                interfaceInfo.implementors.add(classActor);
                for (MethodActor methodActor : interfaceInfo.indirectCalls) {
                    add(classActor.findVirtualMethodActor(methodActor), methodActor, Relationship.IMPLEMENTS);
                }
            }
        }

    }

    private <M extends MethodActor> void addMethods(MethodActor referrer, Iterable<M> methodActors, Relationship relationship) {
        for (M methodActor : methodActors) {
            add(methodActor, referrer, relationship);
        }
    }

    private void addMethods(MethodActor referrer, MethodActor[] methodActors, Relationship relationship) {
        for (MethodActor methodActor : methodActors) {
            add(methodActor, referrer, relationship);
        }
    }

    private void processNewTargetMethod(TargetMethod targetMethod) {
        traceNewTargetMethod(targetMethod);
        final ClassMethodActor classMethodActor = targetMethod.classMethodActor();
        // add the methods referenced in the target method's literals
        if (targetMethod.referenceLiterals() != null) {
            for (Object literal : targetMethod.referenceLiterals()) {
                if (literal instanceof MethodActor) {
                    add((MethodActor) literal, classMethodActor, Relationship.LITERAL);
                }
            }
        }
        final Set<MethodActor> directCalls = new HashSet<MethodActor>();
        final Set<MethodActor> virtualCalls = new HashSet<MethodActor>();
        final Set<MethodActor> interfaceCalls = new HashSet<MethodActor>();
        final Set<MethodActor> inlinedMethods = new HashSet<MethodActor>();
        // gather all direct, virtual, and interface calls and add them
        targetMethod.gatherCalls(directCalls, virtualCalls, interfaceCalls, inlinedMethods);
        addMethods(classMethodActor, directCalls, Relationship.DIRECT_CALL);
        addMethods(classMethodActor, virtualCalls, Relationship.VIRTUAL_CALL);
        addMethods(classMethodActor, interfaceCalls, Relationship.INTERFACE_CALL);

        // if this method (or any that it inlines) contains anonymous classes, add them:
        inlinedMethods.add(classMethodActor);
        for (MethodActor m : inlinedMethods) {
            if (m != null) {
                final Set<ClassActor> anonymousClasses = lookupAnonymousClasses(m);
                if (anonymousClasses != null) {
                    for (ClassActor classActor : anonymousClasses) {
                        processNewClass(classActor);
                    }
                }
            }
        }
    }

    private void traceNewTargetMethod(TargetMethod targetMethod) {
        if (Trace.hasLevel(2)) {
            Trace.line(2, "new target method: " + (targetMethod.classMethodActor() == null ? targetMethod.regionName() : targetMethod.classMethodActor().format("%H.%n(%P)")));
        }
    }

    private final int numberOfCompilerThreads;

    CompiledPrototype(int numberCompilerThreads) {
        vmConfig().initializeSchemes(Phase.COMPILING);
        numberOfCompilerThreads = numberCompilerThreads;
        Trace.line(1, "# compiler threads:" + numberOfCompilerThreads);
    }

    private boolean isIndirectCall(Relationship relationship) {
        return relationship == Relationship.VIRTUAL_CALL || relationship == Relationship.INTERFACE_CALL;
    }

    boolean add(MethodActor methodActor, MethodActor referrer, Relationship relationship) {
        if (methodActor == null) {
            return false;
        }

        if (isIndirectCall(relationship)) {
            // if this is an indirect call that has not been seen before, add all possibly reaching implementations
            // --even if this actual method implementation may not be compiled.
            final ClassInfo info = getInfo(methodActor.holder());
            if (!info.indirectCalls.contains(methodActor)) {
                info.indirectCalls.add(methodActor);
                if (relationship == Relationship.VIRTUAL_CALL) {
                    for (ClassActor subClass : info.subClasses) {
                        add(subClass.findVirtualMethodActor(methodActor), methodActor, Relationship.OVERRIDES);
                    }
                }
                if (relationship == Relationship.INTERFACE_CALL) {
                    for (ClassActor subClass : info.implementors) {
                        add(subClass.findVirtualMethodActor(methodActor), methodActor, Relationship.IMPLEMENTS);
                    }
                }
            }
        }
        if (methodActors.containsKey(methodActor)) {
            // this method is already processed or on the queue.
            return false;
        }

        if (methodActor.isAnnotationPresent(BOOT_IMAGE_DIRECTIVE.class)) {
            final BOOT_IMAGE_DIRECTIVE annotation = methodActor.getAnnotation(BOOT_IMAGE_DIRECTIVE.class);
            if (annotation.keepUnlinked()) {
                // if there is an annotation to keep this method unlinked, add to the unlinked methods set
            } else if (annotation.useJitCompiler()) {
                // if there is an explicit annotation to use the JIT compiler
                registerJitMethod(methodActor);
            } else if (annotation.exclude()) {
                return false;
            }
        }
        if (Actor.isDeclaredFoldable(methodActor.flags())) {
            // All foldable methods must have their stubs precompiled in the image
            final ClassActor stubClassActor = ClassActor.fromJava(methodActor.makeInvocationStub().getClass());
            addMethods(referrer, stubClassActor.localVirtualMethodActors(), relationship);
        }
        methodActors.put(methodActor, new Link(methodActor, referrer, relationship));
        worklist.add(methodActor);
        return true;
    }

    private void addMethodsReferencedByExistingTargetCode() {
        for (TargetMethod targetMethod : Code.bootCodeRegion.targetMethods()) {
            processNewTargetMethod(targetMethod);
        }
    }

    public static void registerJitClass(Class javaClass) {
        ClassActor classActor = ClassActor.fromJava(javaClass);
        for (MethodActor methodActor : classActor.getLocalMethodActors()) {
            recommendedCompiler.put(methodActor, vmConfig().jitCompilerScheme());
        }
    }

    public static void registerC1XClass(Class javaClass) {
        ClassActor classActor = ClassActor.fromJava(javaClass);
        RuntimeCompilerScheme compiler = c1xCompilerScheme();
        for (MethodActor methodActor : classActor.getLocalMethodActors()) {
            recommendedCompiler.put(methodActor, compiler);
        }
    }

    public static boolean forbidCPSCompile(ClassMethodActor classMethodActor) {
        // check whether the method has been recommended to be compiled with another compiler
        return recommendedCompiler.get(classMethodActor) != null;
    }

    public static void registerJitMethod(MethodActor methodActor) {
        recommendedCompiler.put(methodActor, vmConfig().jitCompilerScheme());
    }

    public static void registerC1XMethod(MethodActor methodActor) {
        recommendedCompiler.put(methodActor, c1xCompilerScheme());
    }

    private static synchronized RuntimeCompilerScheme c1xCompilerScheme() {
        if (c1xCompiler == null) {
            RuntimeCompilerScheme compiler = vmConfig().optCompilerScheme();
            if (!compiler.getClass().getSimpleName().equals("C1XCompilerScheme")) {
                compiler = vmConfig().jitCompilerScheme();
                if (!compiler.getClass().getSimpleName().equals("C1XCompilerScheme")) {
                    compiler = vmConfig().bootCompilerScheme();
                    if (!compiler.getClass().getSimpleName().equals("C1XCompilerScheme")) {
                        try {
                            // TODO: remove reflective dependency here!
                            Class<?> type = Class.forName("com.sun.max.vm.compiler.c1x.C1XCompilerScheme");
                            Constructor constructor = type.getConstructor();
                            compiler = (RuntimeCompilerScheme) constructor.newInstance();
                            compiler.initialize(Phase.BOOTSTRAPPING);
                        } catch (Exception e) {
                            throw ProgramError.unexpected(e);
                        }
                    }
                }
            }
            c1xCompiler = compiler;
        }
        return c1xCompiler;
    }

    /**
     * Methods that must be statically compiled in the boot image.
     */
    private static Set<MethodActor> imageMethodActors = new HashSet<MethodActor>();
    private static Set<MethodActor> imageInvocationStubMethodActors = new HashSet<MethodActor>();
    private static Set<MethodActor> imageConstructorStubMethodActors = new HashSet<MethodActor>();

    /**
     * Registers a given method that must be statically compiled in the boot image.
     */
    public static void registerImageMethod(ClassMethodActor m) {
        assert imageMethodActors != null : "too late to add VM entry point " + m;
        ProgramError.check(m != null);
        imageMethodActors.add(m);
    }

    /**
     * Request the given method have a statically generated and compiled invocation stub in the boot image.
     */
    public static void registerImageInvocationStub(MethodActor m) {
        assert imageInvocationStubMethodActors != null : "too late to add VM entry point " + m;
        imageInvocationStubMethodActors.add(m);
    }

    /**
     * Request that the given method have a statically generated and compiled constructor stub in the boot image.
     * @param m
     */
    public static void registerImageConstructorStub(MethodActor m) {
        assert imageConstructorStubMethodActors != null : "too late to add VM entry point " + m;
        imageConstructorStubMethodActors.add(m);
    }

    private boolean vmEntryPointsDone;

    private void addVMEntryPoints() {
        final Relationship vmEntryPoint = null;

        final RunScheme runScheme = vmConfig().runScheme();
        add(ClassRegistry.MaxineVM_run, null, vmEntryPoint);
        add(ClassRegistry.VmThread_run, null, vmEntryPoint);
        add(ClassRegistry.VmThread_attach, null, vmEntryPoint);
        add(ClassRegistry.VmThread_detach, null, vmEntryPoint);
        add(ClassRegistry.findMethod("run", runScheme.getClass()), null, vmEntryPoint);

        addMethods(null, ClassActor.fromJava(JVMFunctions.class).localStaticMethodActors(), vmEntryPoint);
        addMethods(null, imageMethodActors, vmEntryPoint);
        // we would prefer not to invoke stub-generation/compilation for the shutdown hooks procedure, e.g., after an OutOfMemoryError
        try {
            registerImageInvocationStub(ClassActor.fromJava(Class.forName("java.lang.Shutdown")).findLocalStaticMethodActor("shutdown"));
        } catch (ClassNotFoundException classNotFoundException) {
            FatalError.unexpected("cannot load java.lang.Shutdown");
        }

        for (MethodActor methodActor : imageInvocationStubMethodActors) {
            if (methodActor.holder().toJava().isEnum() && methodActor.name.equals("values")) {
                // add a method stub for the "values" method of the enum
                final ClassActor classActor = ClassActor.fromJava(methodActor.holder().toJava());
                final ClassMethodActor valuesMethod = classActor.findLocalClassMethodActor(SymbolTable.makeSymbol("values"), SignatureDescriptor.fromJava(Enum[].class));
                addStaticAndVirtualMethods(JDK_sun_reflect_ReflectionFactory.createPrePopulatedMethodStub(valuesMethod));
            }
            final ClassActor stubClassActor = ClassActor.fromJava(methodActor.makeInvocationStub().getClass());
            addMethods(null, stubClassActor.localVirtualMethodActors(), vmEntryPoint);
        }
        for (MethodActor methodActor : imageConstructorStubMethodActors) {
            addStaticAndVirtualMethods(JDK_sun_reflect_ReflectionFactory.createPrePopulatedConstructorStub(methodActor));
        }

        add(ClassActor.fromJava(DebugBreak.class).findLocalStaticMethodActor("here"), null, vmEntryPoint);
        // pre-compile the dynamic linking methods, which reduces startup time
        add(ClassActor.fromJava(Runtime.class).findLocalVirtualMethodActor("loadLibrary0"), null, vmEntryPoint);
        add(ClassActor.fromJava(Runtime.class).findLocalStaticMethodActor("loadLibrary"), null, vmEntryPoint);
        add(ClassActor.fromJava(System.class).findLocalStaticMethodActor("loadLibrary"), null, vmEntryPoint);
        add(ClassActor.fromJava(ClassLoader.class).findLocalStaticMethodActor("loadLibrary0"), null, vmEntryPoint);
        add(ClassActor.fromJava(ClassLoader.class).findLocalStaticMethodActor("loadLibrary"), null, vmEntryPoint);
        add(ClassActor.fromJava(Classes.forName("java.lang.ProcessEnvironment")).findLocalStaticMethodActor("<clinit>"), null, vmEntryPoint);

        // It's too late now to register any further methods to be compiled into the boot image
        imageMethodActors = null;
        imageConstructorStubMethodActors = null;
        imageInvocationStubMethodActors = null;
    }

    private void addStaticAndVirtualMethods(ClassActor classActor) {
        addMethods(null, classActor.localVirtualMethodActors(), (classActor instanceof InterfaceActor) ? Relationship.INTERFACE_CALL : Relationship.VIRTUAL_CALL);
        addMethods(null, classActor.localStaticMethodActors(), Relationship.DIRECT_CALL);
    }

    private int totalCompilations;

    private boolean compileWorklist() {
        Trace.begin(1, "compile: " + worklist.size() + " new methods");
        final CodeRegion region = Code.bootCodeRegion;
        final Address oldMark = region.getAllocationMark();
        final int initialNumberOfCompilations = totalCompilations;
        final CompilationScheme compilationScheme = vmConfig().compilationScheme();

        if (numberOfCompilerThreads == 1) {
            while (!worklist.isEmpty()) {
                final MethodActor methodActor = worklist.removeFirst();
                if (hasCode(methodActor)) {
                    RuntimeCompilerScheme compiler = recommendedCompiler.get(methodActor);
                    TargetMethod targetMethod = compilationScheme.synchronousCompile((ClassMethodActor) methodActor, compiler);
                    processNewTargetMethod(targetMethod);
                    ++totalCompilations;
                    if (totalCompilations % 200 == 0) {
                        Trace.line(1, "compiled: " + totalCompilations + " (" + methodActors.size() + " methods)");
                    }
                }
            }
        } else {
            int submittedCompilations = totalCompilations;

            final ExecutorService compilationService = Executors.newFixedThreadPool(numberOfCompilerThreads);
            final CompletionService<TargetMethod> compilationCompletionService = new ExecutorCompletionService<TargetMethod>(compilationService);

            while (true) {
                while (!worklist.isEmpty()) {
                    final MethodActor methodActor = worklist.removeFirst();
                    if (hasCode(methodActor)) {
                        ++submittedCompilations;
                        compilationCompletionService.submit(new Callable<TargetMethod>() {
                            public TargetMethod call() throws Exception {
                                try {
                                    RuntimeCompilerScheme compiler = recommendedCompiler.get(methodActor);
                                    TargetMethod result = compilationScheme.synchronousCompile((ClassMethodActor) methodActor, compiler);
                                    assert result != null;
                                    return result;
                                } catch (Throwable error) {
                                    throw reportCompilationError(methodActor, error);
                                }
                            }
                        });
                    }
                }
                if (totalCompilations >= submittedCompilations) {
                    if (!worklist.isEmpty()) {
                        continue;
                    }
                    break;
                }
                try {
                    final TargetMethod targetMethod = compilationCompletionService.take().get();
                    assert targetMethod != null;
                    processNewTargetMethod(targetMethod);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException executionException) {
                    compilationService.shutdownNow();
                    ProgramError.unexpected(executionException.getCause());
                }
                ++totalCompilations;
                if (totalCompilations % 200 == 0) {
                    Trace.line(1, "compiled: " + totalCompilations + " (" + methodActors.size() + " methods)");
                }
            }

            compilationService.shutdown();
        }

        final int newCompilations = totalCompilations - initialNumberOfCompilations;
        Trace.end(1, "new compilations: " + newCompilations);
        if (newCompilations == 0) {
            ProgramError.check(region.getAllocationMark().equals(oldMark));
        }
        return newCompilations > 0;
    }

    private ProgramError reportCompilationError(final MethodActor classMethodActor, Throwable error) throws ProgramError {
        System.err.println("Error occurred while compiling " + classMethodActor + ": " + error);
        System.err.println("Referrer chain:");
        System.err.println("    " + classMethodActor.format("%H.%n(%p)"));
        MethodActor referent = classMethodActor;
        while (referent != null) {
            final Link link = methodActors.get(referent);
            if (link == null) {
                System.err.println("  (no referrer chain available)");
                break;
            }
            if (referent == link.referrer) {
                System.err.println("  which references itself recursively");
                break;
            }
            referent = link.referrer;
            if (referent == null) {
                System.err.println("    which is a VM entry point");
            } else {
                System.err.println("    which " + link.relationship.asReferent + " " + referent.format("%H.%n(%p)"));
            }
        }
        error.printStackTrace(System.err);
        return ProgramError.unexpected("Error occurred while compiling " + classMethodActor, error);
    }

    public void compileFoldableMethods() {
        Trace.begin(1, "compiling foldable methods");
        for (ClassActor classActor : BOOT_CLASS_REGISTRY.copyOfClasses()) {
            classActor.forAllClassMethodActors(new Procedure<ClassMethodActor>() {
                public void run(ClassMethodActor classMethodActor) {
                    if (classMethodActor.isDeclaredFoldable()) {
                        add(classMethodActor, null, null);
                    }
                }
            });
        }
        compileWorklist();
        Trace.end(1, "compiling foldable methods");
    }

    private boolean hasCode(MethodActor methodActor) {
        return methodActor instanceof ClassMethodActor &&
            !methodActor.isAbstract() &&
            !methodActor.isIntrinsic() &&
            (methodActor.isHiddenToReflection() || !methodActor.isBuiltin());
    }

    public void addEntrypoints() {
        // 1. create bootcode region.
        final CodeRegion region = Code.bootCodeRegion;
        region.setSize(Size.fromInt(Integer.MAX_VALUE / 4)); // enable virtually infinite allocations
        // 2. add only entrypoint methods and methods not to be compiled.
        addMethodsReferencedByExistingTargetCode();
        addVMEntryPoints();
    }

    public boolean compile() {
        boolean compiledAny = false;
        boolean compiledSome = false;
        do {
            // 3. add all new class implementations
            gatherNewClasses();
            // 4. compile all new methods
            compiledSome = compileWorklist();
            compiledAny = compiledAny | compiledSome;
        } while (compiledSome);
        return compiledAny;
    }

    private void linkNonVirtualCalls() {
        Trace.begin(1, "linkNonVirtualCalls");
        for (TargetMethod targetMethod : Code.bootCodeRegion.targetMethods()) {
            if (!(targetMethod instanceof Adapter)) {
                Adapter adapter = null;
                ClassMethodActor classMethodActor = targetMethod.classMethodActor;
                if (classMethodActor != null) {
                    AdapterGenerator gen = AdapterGenerator.forCallee(classMethodActor, targetMethod.abi().callEntryPoint);
                    adapter = gen != null ? gen.make(classMethodActor) : null;
                }
                if (!targetMethod.linkDirectCalls(adapter)) {
                    final Object[] directCallees = targetMethod.directCallees();
                    if (directCallees != null) {
                        for (int i = 0; i < directCallees.length; i++) {
                            Object currentDirectCallee = directCallees[i];
                            final TargetMethod callee = targetMethod.getTargetMethod(currentDirectCallee);
                            if (callee == null) {
                                ProgramWarning.message("did not link direct callee " + currentDirectCallee + " in method: " + targetMethod);
                            }
                        }
                    }
                }
            }
        }
        Trace.end(1, "linkNonVirtualCalls");
    }

    private void linkVTableEntries() {
        Trace.begin(1, "linkVTableEntries");
        for (ClassActor classActor : BOOT_CLASS_REGISTRY.copyOfClasses()) {
            if (classActor.isReferenceClassActor()) {
                linkVTable(classActor);
            }
        }
        Trace.end(1, "linkVTableEntries");
    }

    private void linkVTable(ClassActor classActor) {
        final DynamicHub dynamicHub = classActor.dynamicHub();
        for (int vTableIndex = Hub.vTableStartIndex(); vTableIndex < Hub.vTableStartIndex() + dynamicHub.vTableLength(); vTableIndex++) {
            final VirtualMethodActor virtualMethodActor = classActor.getVirtualMethodActorByVTableIndex(vTableIndex);
            final TargetMethod targetMethod = CompilationScheme.Static.getCurrentTargetMethod(virtualMethodActor);
            if (targetMethod != null) {
                dynamicHub.setWord(vTableIndex, VTABLE_ENTRY_POINT.in(targetMethod));
            }
        }
    }

    private void linkITableEntries() {
        Trace.begin(1, "linkITableEntries");

        final IntHashMap<InterfaceActor> serialToInterfaceActor = new IntHashMap<InterfaceActor>();
        ClassActor[] classes = BOOT_CLASS_REGISTRY.copyOfClasses();
        for (ClassActor classActor : classes) {
            if (classActor instanceof InterfaceActor) {
                final InterfaceActor interfaceActor = (InterfaceActor) classActor;
                serialToInterfaceActor.put(interfaceActor.id, interfaceActor);
            }
        }

        for (ClassActor classActor : classes) {
            if (classActor.isReferenceClassActor()) {
                linkITable(classActor, serialToInterfaceActor);
            }
        }
        Trace.end(1, "linkITableEntries");
    }

    private void linkITable(ClassActor classActor, final IntHashMap<InterfaceActor> serialToInterfaceActor) {
        final DynamicHub hub = classActor.dynamicHub();
        for (int mTableIndex = hub.mTableStartIndex; mTableIndex < hub.mTableStartIndex + hub.mTableLength; mTableIndex++) {
            final int interfaceITableIndex = hub.getInt(mTableIndex);
            if (interfaceITableIndex > 0) {
                final int serial = hub.getWord(interfaceITableIndex).asAddress().toInt();
                final InterfaceActor interfaceActor = serialToInterfaceActor.get(serial);
                if (interfaceActor != null) {
                    for (InterfaceMethodActor interfaceMethodActor : interfaceActor.localInterfaceMethodActors()) {
                        final int methodITableIndex = interfaceITableIndex + interfaceMethodActor.iIndexInInterface();
                        final int iIndex = methodITableIndex - hub.iTableStartIndex;
                        final VirtualMethodActor virtualMethodActor = classActor.getVirtualMethodActorByIIndex(iIndex);
                        final TargetMethod targetMethod = CompilationScheme.Static.getCurrentTargetMethod(virtualMethodActor);
                        if (targetMethod != null) {
                            hub.setWord(methodITableIndex, VTABLE_ENTRY_POINT.in(targetMethod));
                        }
                    }
                }
            }
        }
    }

    public void link() {
        linkNonVirtualCalls();
        linkVTableEntries();
        linkITableEntries();
    }
}
