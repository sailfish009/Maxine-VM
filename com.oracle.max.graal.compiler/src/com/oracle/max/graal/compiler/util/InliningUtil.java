/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.max.graal.compiler.util;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.max.criutils.*;
import com.oracle.max.graal.compiler.*;
import com.oracle.max.graal.compiler.graphbuilder.*;
import com.oracle.max.graal.cri.*;
import com.oracle.max.graal.graph.*;
import com.oracle.max.graal.nodes.*;
import com.oracle.max.graal.nodes.DeoptimizeNode.DeoptAction;
import com.oracle.max.graal.nodes.calc.*;
import com.oracle.max.graal.nodes.java.*;
import com.oracle.max.graal.nodes.java.MethodCallTargetNode.InvokeKind;
import com.oracle.max.graal.nodes.util.*;
import com.sun.cri.ci.*;
import com.sun.cri.ri.*;

public class InliningUtil {

    public interface InliningCallback {
        StructuredGraph buildGraph(RiResolvedMethod method);
        double inliningWeight(RiResolvedMethod caller, RiResolvedMethod method, Invoke invoke);
        void recordConcreteMethodAssumption(RiResolvedMethod method, RiResolvedMethod concrete);
    }

    public static String methodName(RiResolvedMethod method) {
        return CiUtil.format("%H.%n(%p):%r", method, false) + " (" + method.codeSize() + " bytes)";
    }

    private static String methodName(RiResolvedMethod method, Invoke invoke) {
        if (invoke != null && invoke.stateAfter() != null) {
            RiMethod parent = invoke.stateAfter().method();
            return parent.name() + "@" + invoke.bci() + ": " + CiUtil.format("%H.%n(%p):%r", method, false) + " (" + method.codeSize() + " bytes)";
        } else {
            return CiUtil.format("%H.%n(%p):%r", method, false) + " (" + method.codeSize() + " bytes)";
        }
    }

    /**
     * Represents an opportunity for inlining at the given invoke, with the given weight and level.
     * The weight is the amortized weight of the additional code - so smaller is better.
     * The level is the number of nested inlinings that lead to this invoke.
     */
    public abstract static class InlineInfo implements Comparable<InlineInfo> {
        public final Invoke invoke;
        public final double weight;
        public final int level;

        public InlineInfo(Invoke invoke, double weight, int level) {
            this.invoke = invoke;
            this.weight = weight;
            this.level = level;
        }

        @Override
        public int compareTo(InlineInfo o) {
            return (weight < o.weight) ? -1 : (weight > o.weight) ? 1 : 0;
        }

        public abstract void inline(StructuredGraph graph, GraalRuntime runtime, InliningCallback callback);
    }

    /**
     * Represents an inlining opportunity where an intrinsification can take place. Weight and level are always zero.
     */
    private static class IntrinsicInlineInfo extends InlineInfo {
        public final StructuredGraph intrinsicGraph;

        public IntrinsicInlineInfo(Invoke invoke, StructuredGraph intrinsicGraph) {
            super(invoke, 0, 0);
            this.intrinsicGraph = intrinsicGraph;
        }

        @Override
        public void inline(StructuredGraph compilerGraph, GraalRuntime runtime, InliningCallback callback) {
            InliningUtil.inline(invoke, intrinsicGraph, true);
        }

        @Override
        public String toString() {
            return "intrinsic inlining " + CiUtil.format("%H.%n(%p):%r", invoke.callTarget().targetMethod(), false);
        }
    }

    /**
     * Represents an inlining opportunity where the compiler can statically determine a monomorphic target method and therefore is able to determine the called method exactly.
     */
    private static class StaticInlineInfo extends InlineInfo {
        public final RiResolvedMethod concrete;

        public StaticInlineInfo(Invoke invoke, double weight, int level, RiResolvedMethod concrete) {
            super(invoke, weight, level);
            this.concrete = concrete;
        }

        @Override
        public void inline(StructuredGraph compilerGraph, GraalRuntime runtime, InliningCallback callback) {
            StructuredGraph graph = GraphBuilderPhase.cachedGraphs.get(concrete);
            if (graph != null) {
                if (GraalOptions.TraceInlining) {
                    TTY.println("Reusing graph for %s", methodName(concrete, invoke));
                }
            } else {
                if (GraalOptions.TraceInlining) {
                    TTY.println("Building graph for %s, locals: %d, stack: %d", methodName(concrete, invoke), concrete.maxLocals(), concrete.maxStackSize());
                }
                graph = callback.buildGraph(concrete);
            }

            InliningUtil.inline(invoke, graph, true);
        }

        @Override
        public String toString() {
            return "static inlining " + CiUtil.format("%H.%n(%p):%r", concrete, false);
        }
    }

    /**
     * Represents an inlining opportunity for which profiling information suggests a monomorphic receiver, but for which the receiver type cannot be proven.
     * A type check guard will be generated if this inlining is performed.
     */
    private static class TypeGuardInlineInfo extends StaticInlineInfo {

        public final RiResolvedType type;
        public final double probability;

        public TypeGuardInlineInfo(Invoke invoke, double weight, int level, RiResolvedMethod concrete, RiResolvedType type, double probability) {
            super(invoke, weight, level, concrete);
            this.type = type;
            this.probability = probability;
        }

        @Override
        public void inline(StructuredGraph graph, GraalRuntime runtime, InliningCallback callback) {
            IsTypeNode isType = graph.unique(new IsTypeNode(invoke.callTarget().receiver(), type));
            FixedGuardNode guard = graph.add(new FixedGuardNode(isType));
            assert invoke.predecessor() != null;
            invoke.predecessor().replaceFirstSuccessor(invoke.node(), guard);
            guard.setNext(invoke.node());

            if (GraalOptions.TraceInlining) {
                TTY.println("inlining with type check, type probability: %5.3f", probability);
            }
            super.inline(graph, runtime, callback);
        }

        @Override
        public String toString() {
            return "type-checked inlining " + CiUtil.format("%H.%n(%p):%r", concrete, false);
        }
    }

    /**
     * Represents an inlining opportunity where the current class hierarchy leads to a monomorphic target method, but for which an assumption has to be registered because of non-final classes.
     */
    private static class AssumptionInlineInfo extends StaticInlineInfo {

        public AssumptionInlineInfo(Invoke invoke, double weight, int level, RiResolvedMethod concrete) {
            super(invoke, weight, level, concrete);
        }

        @Override
        public void inline(StructuredGraph graph, GraalRuntime runtime, InliningCallback callback) {
            if (GraalOptions.TraceInlining) {
                String targetName = CiUtil.format("%H.%n(%p):%r", invoke.callTarget().targetMethod(), false);
                String concreteName = CiUtil.format("%H.%n(%p):%r", concrete, false);
                TTY.println("recording concrete method assumption: %s -> %s", targetName, concreteName);
            }
            callback.recordConcreteMethodAssumption(invoke.callTarget().targetMethod(), concrete);
            super.inline(graph, runtime, callback);
        }

        @Override
        public String toString() {
            return "inlining with assumption " + CiUtil.format("%H.%n(%p):%r", concrete, false);
        }
    }

    /**
     * Determines if inlining is possible at the given invoke node.
     * @param invoke the invoke that should be inlined
     * @param level the number of nested inlinings that lead to this invoke, or 0 if the invoke was part of the initial graph
     * @param runtime a GraalRuntime instance used to determine of the invoke can be inlined and/or should be intrinsified
     * @param callback a callback that is used to determine the weight of a specific inlining
     * @return an instance of InlineInfo, or null if no inlining is possible at the given invoke
     */
    public static InlineInfo getInlineInfo(Invoke invoke, int level, GraalRuntime runtime, CiAssumptions assumptions, InliningCallback callback) {
        if (!checkInvokeConditions(invoke)) {
            return null;
        }
        RiResolvedMethod parent = invoke.stateAfter().method();
        MethodCallTargetNode callTarget = invoke.callTarget();
        StructuredGraph intrinsicGraph = runtime.intrinsicGraph(parent, invoke.bci(), callTarget.targetMethod(), callTarget.arguments());
        if (intrinsicGraph != null) {
            return new IntrinsicInlineInfo(invoke, intrinsicGraph);
        }

        if (callTarget.invokeKind() == InvokeKind.Special || callTarget.targetMethod().canBeStaticallyBound()) {
            if (checkTargetConditions(callTarget.targetMethod(), runtime)) {
                double weight = callback.inliningWeight(parent, callTarget.targetMethod(), invoke);
                return new StaticInlineInfo(invoke, weight, level, callTarget.targetMethod());
            }
            return null;
        }
        if (callTarget.receiver().exactType() != null) {
            RiResolvedType exact = callTarget.receiver().exactType();
            assert exact.isSubtypeOf(callTarget.targetMethod().holder()) : exact + " subtype of " + callTarget.targetMethod().holder();
            RiResolvedMethod resolved = exact.resolveMethodImpl(callTarget.targetMethod());
            if (checkTargetConditions(resolved, runtime)) {
                double weight = callback.inliningWeight(parent, resolved, invoke);
                return new StaticInlineInfo(invoke, weight, level, resolved);
            }
            return null;
        }
        RiResolvedType holder = callTarget.targetMethod().holder();

        if (callTarget.receiver().declaredType() != null) {
            RiType declared = callTarget.receiver().declaredType();
            // the invoke target might be more specific than the holder (happens after inlining: locals lose their declared type...)
            // TODO (ls) fix this
            if (declared instanceof RiResolvedType && ((RiResolvedType) declared).isSubtypeOf(holder)) {
                holder = (RiResolvedType) declared;
            }
        }
        // TODO (tw) fix this
        if (assumptions == null) {
            return null;
        }
        RiResolvedMethod concrete = holder.uniqueConcreteMethod(callTarget.targetMethod());
        if (concrete != null) {
            if (checkTargetConditions(concrete, runtime)) {
                double weight = callback.inliningWeight(parent, concrete, invoke);
                return new AssumptionInlineInfo(invoke, weight, level, concrete);
            }
            return null;
        }
        RiTypeProfile profile = parent.typeProfile(invoke.bci());
        if (profile != null && profile.probabilities != null && profile.probabilities.length > 0 && profile.morphism == 1) {
            if (GraalOptions.InlineWithTypeCheck) {
                // type check and inlining...
                concrete = profile.types[0].resolveMethodImpl(callTarget.targetMethod());
                if (concrete != null && checkTargetConditions(concrete, runtime)) {
                    double weight = callback.inliningWeight(parent, concrete, invoke);
                    return new TypeGuardInlineInfo(invoke, weight, level, concrete, profile.types[0], profile.probabilities[0]);
                }
                return null;
            } else {
                if (GraalOptions.TraceInlining) {
                    TTY.println("not inlining %s because GraalOptions.InlineWithTypeCheck == false", methodName(callTarget.targetMethod(), invoke));
                }
                return null;
            }
        } else {
            if (GraalOptions.TraceInlining) {
                TTY.println("not inlining %s because no monomorphic receiver could be found", methodName(callTarget.targetMethod(), invoke));
            }
            return null;
        }
    }

    private static boolean checkInvokeConditions(Invoke invoke) {
        if (!invoke.canInline()) {
            if (GraalOptions.TraceInlining) {
                TTY.println("not inlining %s because the invoke is manually set to be non-inlinable", methodName(invoke.callTarget().targetMethod(), invoke));
            }
            return false;
        }
        if (invoke.stateAfter() == null) {
            if (GraalOptions.TraceInlining) {
                TTY.println("not inlining %s because the invoke has no after state", methodName(invoke.callTarget().targetMethod(), invoke));
            }
            return false;
        }
        if (invoke.stateAfter().locksSize() > 0) {
            if (GraalOptions.TraceInlining) {
                TTY.println("not inlining %s because of locks", methodName(invoke.callTarget().targetMethod(), invoke));
            }
            return false;
        }
        if (invoke.predecessor() == null) {
            if (GraalOptions.TraceInlining) {
                TTY.println("not inlining %s because the invoke is dead code", methodName(invoke.callTarget().targetMethod(), invoke));
            }
            return false;
        }
        if (invoke.stateAfter() == null) {
            if (GraalOptions.TraceInlining) {
                TTY.println("not inlining %s because of missing frame state", methodName(invoke.callTarget().targetMethod(), invoke));
            }
        }
        return true;
    }

    private static boolean checkTargetConditions(RiMethod method, GraalRuntime runtime) {
        if (!(method instanceof RiResolvedMethod)) {
            if (GraalOptions.TraceInlining) {
                TTY.println("not inlining %s because it is unresolved", method.toString());
            }
            return false;
        }
        RiResolvedMethod resolvedMethod = (RiResolvedMethod) method;
        if (runtime.mustNotInline(resolvedMethod)) {
            if (GraalOptions.TraceInlining) {
                TTY.println("not inlining %s because the CRI set it to be non-inlinable", methodName(resolvedMethod));
            }
            return false;
        }
        if (Modifier.isNative(resolvedMethod.accessFlags())) {
            if (GraalOptions.TraceInlining) {
                TTY.println("not inlining %s because it is a native method", methodName(resolvedMethod));
            }
            return false;
        }
        if (Modifier.isAbstract(resolvedMethod.accessFlags())) {
            if (GraalOptions.TraceInlining) {
                TTY.println("not inlining %s because it is an abstract method", methodName(resolvedMethod));
            }
            return false;
        }
        if (!resolvedMethod.holder().isInitialized()) {
            if (GraalOptions.TraceInlining) {
                TTY.println("not inlining %s because of non-initialized class", methodName(resolvedMethod));
            }
            return false;
        }
        return true;
    }

    /**
     * Performs an actual inlining, thereby replacing the given invoke with the given inlineGraph.
     * @param invoke the invoke that will be replaced
     * @param inlineGraph the graph that the invoke will be replaced with
     * @param receiverNullCheck true if a null check needs to be generated for non-static inlinings, false if no such check is required
     */
    public static void inline(Invoke invoke, StructuredGraph inlineGraph, boolean receiverNullCheck) {
        NodeInputList<ValueNode> parameters = invoke.callTarget().arguments();
        StructuredGraph graph = invoke.node().graph();

        FrameState stateAfter = invoke.stateAfter();

        IdentityHashMap<Node, Node> replacements = new IdentityHashMap<Node, Node>();
        ArrayList<Node> nodes = new ArrayList<Node>();
        ArrayList<Node> frameStates = new ArrayList<Node>();
        ReturnNode returnNode = null;
        UnwindNode unwindNode = null;
        BeginNode entryPointNode = inlineGraph.start();
        FixedNode firstCFGNode = entryPointNode.next();
        for (Node node : inlineGraph.getNodes()) {
            if (node == entryPointNode || node == entryPointNode.stateAfter()) {
                // Do nothing.
            } else if (node instanceof LocalNode) {
                replacements.put(node, parameters.get(((LocalNode) node).index()));
            } else {
                nodes.add(node);
                if (node instanceof ReturnNode) {
                    returnNode = (ReturnNode) node;
                } else if (node instanceof UnwindNode) {
                    unwindNode = (UnwindNode) node;
                } else if (node instanceof FrameState) {
                    frameStates.add(node);
                }
            }
        }

        assert invoke.node().successors().first() != null : invoke;
        assert invoke.node().predecessor() != null;

        Map<Node, Node> duplicates = graph.addDuplicates(nodes, replacements);

        FixedNode firstCFGNodeDuplicate = (FixedNode) duplicates.get(firstCFGNode);
        FixedNode invokeReplacement;
        MethodCallTargetNode callTarget = invoke.callTarget();
        if (callTarget.isStatic() || !receiverNullCheck || parameters.get(0).kind() != CiKind.Object) {
            invokeReplacement = firstCFGNodeDuplicate;
        } else {
            FixedGuardNode guard = graph.add(new FixedGuardNode(graph.unique(new NullCheckNode(parameters.get(0), false))));
            guard.setNext(firstCFGNodeDuplicate);
            invokeReplacement = guard;
        }
        invoke.node().replaceAtPredecessors(invokeReplacement);

        FrameState stateBefore = null;
        double invokeProbability = invoke.node().probability();
        for (Node node : duplicates.values()) {
            if (GraalOptions.ProbabilityAnalysis) {
                if (node instanceof FixedNode) {
                    FixedNode fixed = (FixedNode) node;
                    fixed.setProbability(fixed.probability() * invokeProbability);
                }
            }
            if (node instanceof FrameState) {
                FrameState frameState = (FrameState) node;
                if (frameState.bci == FrameState.BEFORE_BCI) {
                    if (stateBefore == null) {
                        stateBefore = stateAfter.duplicateModified(invoke.bci(), false, invoke.node().kind(), parameters.toArray(new ValueNode[parameters.size()]));
                    }
                    frameState.replaceAndDelete(stateBefore);
                } else if (frameState.bci == FrameState.AFTER_BCI) {
                    frameState.replaceAndDelete(stateAfter);
                }
            }
        }

        int monitorIndexDelta = stateAfter.locksSize();
        if (monitorIndexDelta > 0) {
            for (Map.Entry<Node, Node> entry : duplicates.entrySet()) {
                if (entry.getValue() instanceof AccessMonitorNode) {
                    AccessMonitorNode access = (AccessMonitorNode) entry.getValue();
                    access.setMonitorIndex(access.monitorIndex() + monitorIndexDelta);
                }
            }
        }

        if (returnNode != null) {
            for (Node usage : invoke.node().usages().snapshot()) {
                if (returnNode.result() instanceof LocalNode) {
                    usage.replaceFirstInput(invoke.node(), replacements.get(returnNode.result()));
                } else {
                    usage.replaceFirstInput(invoke.node(), duplicates.get(returnNode.result()));
                }
            }
            Node returnDuplicate = duplicates.get(returnNode);
            returnDuplicate.clearInputs();
            Node n = invoke.next();
            invoke.setNext(null);
            returnDuplicate.replaceAndDelete(n);
        }

        if (invoke instanceof InvokeWithExceptionNode) {
            InvokeWithExceptionNode invokeWithException = ((InvokeWithExceptionNode) invoke);
            if (unwindNode != null) {
                assert unwindNode.predecessor() != null;
                assert invokeWithException.exceptionEdge().successors().explicitCount() == 1;
                ExceptionObjectNode obj = (ExceptionObjectNode) invokeWithException.exceptionEdge().next();

                UnwindNode unwindDuplicate = (UnwindNode) duplicates.get(unwindNode);
                for (Node usage : obj.usages().snapshot()) {
                    usage.replaceFirstInput(obj, unwindDuplicate.exception());
                }
                unwindDuplicate.clearInputs();
                Node n = obj.next();
                obj.setNext(null);
                unwindDuplicate.replaceAndDelete(n);
            } else {
                FixedNode nodeToDelete = invokeWithException.exceptionEdge();
                invokeWithException.setExceptionEdge(null);
                GraphUtil.killCFG(nodeToDelete);
            }
        } else {
            if (unwindNode != null) {
                UnwindNode unwindDuplicate = (UnwindNode) duplicates.get(unwindNode);
                unwindDuplicate.replaceAndDelete(graph.add(new DeoptimizeNode(DeoptAction.InvalidateRecompile)));
            }
        }

        invoke.node().clearInputs();
        GraphUtil.killCFG(invoke.node());

        // adjust all frame states that were copied
        if (frameStates.size() > 0) {
            FrameState outerFrameState = stateAfter.duplicateModified(invoke.bci(), stateAfter.rethrowException(), invoke.node().kind());
            for (Node node : frameStates) {
                FrameState frameState = (FrameState) duplicates.get(node);
                if (!frameState.isDeleted()) {
                    frameState.setOuterFrameState(outerFrameState);
                }
            }
        }

        if (stateAfter.usages().isEmpty()) {
            stateAfter.delete();
        }
    }
}
