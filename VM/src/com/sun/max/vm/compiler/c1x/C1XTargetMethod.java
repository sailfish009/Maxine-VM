/*
 * Copyright (c) 2009 Sun Microsystems, Inc.  All rights reserved.
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
package com.sun.max.vm.compiler.c1x;

import static com.sun.max.platform.Platform.*;
import static com.sun.max.vm.MaxineVM.*;
import static com.sun.max.vm.stack.amd64.AMD64OptStackWalking.*;

import java.io.*;
import java.util.*;

import com.sun.cri.ci.*;
import com.sun.cri.ci.CiTargetMethod.DataPatch;
import com.sun.cri.ci.CiTargetMethod.ExceptionHandler;
import com.sun.cri.ci.CiTargetMethod.Site;
import com.sun.cri.ri.*;
import com.sun.max.annotate.*;
import com.sun.max.io.*;
import com.sun.max.lang.*;
import com.sun.max.platform.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.bytecode.*;
import com.sun.max.vm.code.*;
import com.sun.max.vm.collect.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.compiler.target.amd64.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.stack.*;
import com.sun.max.vm.stack.StackFrameWalker.Cursor;
import com.sun.max.vm.stack.amd64.*;

/**
 * This class implements a {@link TargetMethod target method} for
 * the Maxine VM that represents a compiled method generated by C1X.
 *
 * @author Ben L. Titzer
 * @author Thomas Wuerthinger
 */
public class C1XTargetMethod extends TargetMethod implements Cloneable {

    /**
     * An array of pairs denoting the code positions protected by an exception handler.
     * A pair {@code {p,h}} at index {@code i} in this array specifies that code position
     * {@code h} is the handler for an exception of type {@code t} occurring at position
     * {@code p} where {@code t} is the element at index {@code i / 2} in {@link #exceptionClassActors}.
     */
    private int[] exceptionPositionsToCatchPositions;

    /**
     * @see #exceptionPositionsToCatchPositions
     */
    private ClassActor[] exceptionClassActors;

    /**
     * The frame and register reference maps for this target method.
     *
     * The format of this byte array is described by the following pseudo C declaration:
     * <p>
     *
     * <pre>
     * referenceMaps {
     *     {
     *         u1 frameMap[frameReferenceMapSize];
     *         u1 registerMap[registerReferenceMapSize];
     *     } directCallMaps[numberOfDirectCalls]
     *     {
     *         u1 frameMap[frameReferenceMapSize];
     *         u1 registerMap[registerReferenceMapSize];
     *     } indirectCallMaps[numberOfIndirectCalls]
     *     {
     *         u1 frameMap[frameReferenceMapSize];
     *         u1 registerMap[registerReferenceMapSize];
     *     } safepointMaps[numberOfSafepoints]
     * }
     * </pre>
     */
    private byte[] referenceMaps;

    private Object sourceInfo;

    private ClassMethodActor[] sourceMethods;

    @HOSTED_ONLY
    private CiTargetMethod bootstrappingCiTargetMethod;

    public C1XTargetMethod(ClassMethodActor classMethodActor, CiTargetMethod ciTargetMethod) {
        super(classMethodActor, CallEntryPoint.OPTIMIZED_ENTRY_POINT);
        init(ciTargetMethod);

        if (printTargetMethods.getValue() != null) {
            if (classMethodActor.format("%H.%n").contains(printTargetMethods.getValue())) {
                Log.println(traceToString());
            }
        }
    }

    public C1XTargetMethod(Flavor flavor, String stubName, CiTargetMethod ciTargetMethod) {
        super(flavor, stubName, CallEntryPoint.OPTIMIZED_ENTRY_POINT);
        init(ciTargetMethod);

        if (printTargetMethods.getValue() != null) {
            if (stubName.contains(printTargetMethods.getValue())) {
                Log.println(traceToString());
            }
        }
    }

    private void init(CiTargetMethod ciTargetMethod) {

        if (MaxineVM.isHosted()) {
            // Save the target method for later gathering of calls and duplication
            this.bootstrappingCiTargetMethod = ciTargetMethod;
        }

        initCodeBuffer(ciTargetMethod);
        initFrameLayout(ciTargetMethod);
        initStopPositions(ciTargetMethod);
        initExceptionTable(ciTargetMethod);

        if (!MaxineVM.isHosted()) {
            Adapter adapter = null;
            AdapterGenerator generator = AdapterGenerator.forCallee(this);
            if (generator != null) {
                adapter = generator.make(classMethodActor);
            }
            linkDirectCalls(adapter);
        }
    }

    @Override
    public byte[] referenceMaps() {
        return referenceMaps;
    }

    /**
     * Gets the size (in bytes) of a bit map covering all the registers that may store references.
     * The bit position of a register in the bit map is the register's {@linkplain CiRegister#encoding encoding}.
     */
    @FOLD
    private static int registerReferenceMapSize() {
        return ByteArrayBitMap.computeBitMapSize(target().arch.registerReferenceMapBitCount);
    }

    /**
     * @return the size of an activation frame for this target method in words.
     */
    @UNSAFE
    private int frameWords() {
        return frameSize() / Word.size();
    }

    /**
     * @return the size (in bytes) of a reference map covering an activation frame for this target method.
     */
    private int frameReferenceMapSize() {
        return ByteArrayBitMap.computeBitMapSize(frameWords());
    }

    /**
     * @return the number of bytes in {@link #referenceMaps} corresponding to one stop position.
     */
    private int totalReferenceMapSize() {
        return registerReferenceMapSize() + frameReferenceMapSize();
    }

    private void initCodeBuffer(CiTargetMethod ciTargetMethod) {
        // Create the arrays for the scalar and the object reference literals
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<Object> objectReferences = new ArrayList<Object>();
        int[] relativeDataPos = serializeLiterals(ciTargetMethod, output, objectReferences);
        byte[] scalarLiterals = output.toByteArray();
        Object[] referenceLiterals = objectReferences.toArray();

        // Allocate and set the code and data buffer
        final TargetBundleLayout targetBundleLayout = new TargetBundleLayout(scalarLiterals.length, referenceLiterals.length, ciTargetMethod.targetCodeSize());
        Code.allocate(targetBundleLayout, this);
        this.setData(scalarLiterals, referenceLiterals, ciTargetMethod.targetCode());

        // Patch relative instructions in the code buffer
        patchInstructions(targetBundleLayout, ciTargetMethod, relativeDataPos);
    }

    private int[] serializeLiterals(CiTargetMethod ciTargetMethod, ByteArrayOutputStream output, List<Object> objectReferences) {
        Endianness endianness = Platform.platform().endianness();
        int[] relativeDataPos = new int[ciTargetMethod.dataReferences.size()];
        int z = 0;
        int currentPos = 0;
        for (DataPatch site : ciTargetMethod.dataReferences) {
            final CiConstant data = site.constant;
            relativeDataPos[z] = currentPos;

            try {
                switch (data.kind) {
                    case Double:
                        endianness.writeLong(output, Double.doubleToLongBits(data.asDouble()));
                        currentPos += Long.SIZE / Byte.SIZE;
                        break;

                    case Float:
                        endianness.writeInt(output, Float.floatToIntBits(data.asFloat()));
                        currentPos += Integer.SIZE / Byte.SIZE;
                        break;

                    case Int:
                        endianness.writeInt(output, data.asInt());
                        currentPos += Integer.SIZE / Byte.SIZE;
                        break;

                    case Long:
                        endianness.writeLong(output, data.asLong());
                        currentPos += Long.SIZE / Byte.SIZE;
                        break;

                    case Object:
                        objectReferences.add(data.asObject());
                        break;

                    default:
                        throw new IllegalArgumentException("Unknown constant type!");
                }

            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            // Align on double word boundary
            while (currentPos % (Platform.platform().wordWidth().numberOfBytes * 2) != 0) {
                output.write(0);
                currentPos++;
            }

            z++;
        }

        return relativeDataPos;
    }

    @UNSAFE
    private void patchInstructions(TargetBundleLayout targetBundleLayout, CiTargetMethod ciTargetMethod, int[] relativeDataPositions) {
        Offset codeStart = targetBundleLayout.cellOffset(TargetBundleLayout.ArrayField.code);

        Offset dataDiff = Offset.zero();
        if (this.scalarLiterals != null) {
            Offset dataStart = targetBundleLayout.cellOffset(TargetBundleLayout.ArrayField.scalarLiterals);
            dataDiff = dataStart.minus(codeStart).asOffset();
        }

        Offset referenceDiff = Offset.zero();
        if (this.referenceLiterals() != null) {
            Offset referenceStart = targetBundleLayout.cellOffset(TargetBundleLayout.ArrayField.referenceLiterals);
            referenceDiff = referenceStart.minus(codeStart).asOffset();
        }

        int objectReferenceIndex = 0;
        int refSize = Platform.platform().wordWidth().numberOfBytes;

        int z = 0;
        for (DataPatch site : ciTargetMethod.dataReferences) {

            switch (site.constant.kind) {

                case Double: // fall through
                case Float: // fall through
                case Int: // fall through
                case Long:
                    patchRelativeInstruction(site.pcOffset, dataDiff.plus(relativeDataPositions[z] - site.pcOffset).toInt());
                    break;

                case Object:
                    patchRelativeInstruction(site.pcOffset, referenceDiff.plus(objectReferenceIndex * refSize - site.pcOffset).toInt());
                    objectReferenceIndex++;
                    break;

                default:
                    throw new IllegalArgumentException("Unknown constant type!");
            }

            z++;
        }
    }

    private void patchRelativeInstruction(int codePos, int displacement) {
        X86InstructionDecoder.patchRelativeInstruction(code(), codePos, displacement);
    }

    private void initFrameLayout(CiTargetMethod ciTargetMethod) {
        this.setFrameSize(ciTargetMethod.frameSize());
        this.setRegisterRestoreEpilogueOffset(ciTargetMethod.registerRestoreEpilogueOffset());
    }

    private void initStopPositions(CiTargetMethod ciTargetMethod) {
        int numberOfIndirectCalls = ciTargetMethod.indirectCalls.size();
        int numberOfSafepoints = ciTargetMethod.safepoints.size();
        int totalStopPositions = ciTargetMethod.directCalls.size() + numberOfIndirectCalls + numberOfSafepoints;

        int totalRefMapSize = totalReferenceMapSize();
        referenceMaps = new byte[totalRefMapSize * totalStopPositions];

        int index = 0;
        int[] stopPositions = new int[totalStopPositions];
        Object[] directCallees = new Object[ciTargetMethod.directCalls.size()];

        CiDebugInfo[] debugInfos = new CiDebugInfo[totalStopPositions];
        boolean hasInlinedMethods = false;

        for (CiTargetMethod.Call site : ciTargetMethod.directCalls) {
            hasInlinedMethods |= initStopPosition(index, index * totalRefMapSize, stopPositions, site.pcOffset, site.debugInfo, debugInfos);

            RiMethod riMethod = site.method;
            if (riMethod != null) {
                final ClassMethodActor cma = (ClassMethodActor) riMethod;
                assert cma != null : "unresolved direct call!";
                directCallees[index] = cma;
            } else if (site.runtimeCall != null) {
                final ClassMethodActor cma = C1XRuntimeCalls.getClassMethodActor(site.runtimeCall);
                assert cma != null : "unresolved runtime call!";
                directCallees[index] = cma;
            } else {
                assert site.globalStubID != null;
                TargetMethod globalStubMethod = (TargetMethod) site.globalStubID;
                directCallees[index] = globalStubMethod;
            }
            index++;
        }

        for (CiTargetMethod.Call site : ciTargetMethod.indirectCalls) {
            hasInlinedMethods |= initStopPosition(index, index * totalRefMapSize, stopPositions, site.pcOffset, site.debugInfo, debugInfos);
            if (site.symbol != null) {
                stopPositions[index] |= StopPositions.NATIVE_FUNCTION_CALL;
            }
            index++;
        }

        for (CiTargetMethod.Safepoint site : ciTargetMethod.safepoints) {
            hasInlinedMethods |= initStopPosition(index, index * totalRefMapSize, stopPositions, site.pcOffset, site.debugInfo, debugInfos);
            index++;
        }

        setStopPositions(stopPositions, directCallees, numberOfIndirectCalls, numberOfSafepoints);
        initSourceInfo(debugInfos, hasInlinedMethods);
    }

    private boolean initStopPosition(int index, int refmapIndex, int[] stopPositions, int codePos, CiDebugInfo debugInfo, CiDebugInfo[] debugInfos) {
        stopPositions[index] = codePos;
        if (debugInfo != null) {
            // remember the code position
            debugInfos[index] = debugInfo;
            // copy the stack map
            int frameRefMapBytes;
            if (debugInfo.hasStackRefMap()) {
                frameRefMapBytes = debugInfo.frameRefMap.copyTo(referenceMaps, refmapIndex, -1);
            } else {
                frameRefMapBytes = 0;
            }
            // copy the register map
            if (debugInfo.hasRegisterRefMap()) {
                int regRefMapSize = registerReferenceMapSize();
                debugInfo.registerRefMap.copyTo(referenceMaps, refmapIndex + frameRefMapBytes, regRefMapSize);
            }
            return debugInfo.codePos != null && debugInfo.codePos.caller != null;
        }
        return false;
    }

    private void initExceptionTable(CiTargetMethod ciTargetMethod) {
        if (ciTargetMethod.exceptionHandlers.size() > 0) {
            exceptionPositionsToCatchPositions = new int[ciTargetMethod.exceptionHandlers.size() * 2];
            exceptionClassActors = new ClassActor[ciTargetMethod.exceptionHandlers.size()];

            int z = 0;
            for (ExceptionHandler handler : ciTargetMethod.exceptionHandlers) {
                exceptionPositionsToCatchPositions[z * 2] = handler.pcOffset;
                exceptionPositionsToCatchPositions[z * 2 + 1] = handler.handlerPos;
                exceptionClassActors[z] = (handler.exceptionType == null) ? null : (ClassActor) handler.exceptionType;
                z++;
            }
        }
    }

    private void initSourceInfo(CiDebugInfo[] debugInfos, boolean hasInlinedMethods) {
        if (hasInlinedMethods) {
            // the stop information is stored less compactly if there are inlined methods;
            // store the class method actor, the bytecode index, and the inlining parent
            IdentityHashMap<CiCodePos, Integer> codePosMap = new IdentityHashMap<CiCodePos, Integer>();
            IdentityHashMap<ClassMethodActor, Integer> inlinedMethodMap = new IdentityHashMap<ClassMethodActor, Integer>();
            ArrayList<ClassMethodActor> inlinedMethodList = new ArrayList<ClassMethodActor>(5);
            ArrayList<CiCodePos> extraList = new ArrayList<CiCodePos>(5);

            // build the list of extra source info entries
            for (int i = 0; i < debugInfos.length; i++) {
                CiDebugInfo debugInfo = debugInfos[i];
                if (debugInfo != null) {
                    CiCodePos curPos = debugInfo.codePos;
                    if (curPos != null) {
                        // there is source information here
                        codePosMap.put(curPos, i);
                        CiCodePos pos = curPos.caller;
                        while (pos != null) {
                            // add entries for the caller positions
                            if (codePosMap.get(pos) == null) {
                                codePosMap.put(pos, -extraList.size() - 1);
                                extraList.add(pos);
                            }
                            pos = pos.caller;
                        }
                    }
                }
            }

            int[] sourceInfoData = new int[(extraList.size() + debugInfos.length) * 3];
            int index = 0;
            for (; index < debugInfos.length; index++) {
                // there is source information here
                CiDebugInfo debugInfo = debugInfos[index];
                if (debugInfo != null && debugInfo.codePos != null) {
                    encodeSourcePos(index, sourceInfoData, debugInfo.codePos, inlinedMethodMap, codePosMap, debugInfos.length, inlinedMethodList);
                }
            }

            for (CiCodePos codePos : extraList) {
                // there is source information here
                encodeSourcePos(index++, sourceInfoData, codePos, inlinedMethodMap, codePosMap, debugInfos.length, inlinedMethodList);
            }

            this.sourceInfo = sourceInfoData;
            this.sourceMethods = inlinedMethodList.toArray(new ClassMethodActor[inlinedMethodList.size()]);

        } else if (debugInfos.length > 0) {
            // use a more compact format if there are no inlined methods;
            // only store the bytecode index in the originating method for each stop
            char[] bciInfo = new char[debugInfos.length];
            this.sourceInfo = bciInfo;
            for (int i = 0; i < debugInfos.length; i++) {
                CiDebugInfo debugInfo = debugInfos[i];
                if (debugInfo != null && debugInfo.codePos != null) {
                    bciInfo[i] = (char) debugInfo.codePos.bci;
                } else {
                    bciInfo[i] = (char) -1;
                }
            }
        }
    }

    private void encodeSourcePos(int index,
                                 int[] sourceInfoData,
                                 CiCodePos curPos,
                                 IdentityHashMap<ClassMethodActor, Integer> inlinedMethodMap,
                                 IdentityHashMap<CiCodePos, Integer> codePosMap,
                                 int stopCount,
                                 List<ClassMethodActor> inlinedMethodList) {
        // encodes three integers into the sourceInfoData array:
        // the index into the sourceMethods array, the bytecode index, and the index of the caller method
        // (if this entry is an inlined method)
        int start = index * 3;

        ClassMethodActor cma = (ClassMethodActor) curPos.method;
        Integer methodIndex = inlinedMethodMap.get(cma);
        if (methodIndex == null) {
            methodIndex = inlinedMethodList.size();
            inlinedMethodMap.put(cma, methodIndex);
            inlinedMethodList.add(cma);
        }
        int bytecodeIndex = curPos.bci;
        int callerIndex;
        if (curPos.caller == null) {
            callerIndex = -1;
        } else {
            Integer sourceInfoIndex = codePosMap.get(curPos.caller);
            callerIndex = sourceInfoIndex < 0 ? (-sourceInfoIndex - 1) + stopCount : sourceInfoIndex;
        }
        sourceInfoData[start] = methodIndex;
        sourceInfoData[start + 1] = bytecodeIndex;
        sourceInfoData[start + 2] = callerIndex;
    }

    @Override
    public boolean isPatchableCallSite(Address callSite) {
        return AMD64TargetMethodUtil.isPatchableCallSite(callSite);
    }

    @UNSAFE
    @Override
    public final void fixupCallSite(int callOffset, Address callEntryPoint) {
        final int displacement = callEntryPoint.asAddress().minus(codeStart().plus(callOffset)).toInt();
        X86InstructionDecoder.patchRelativeInstruction(code(), callOffset, displacement);
    }

    @UNSAFE
    @Override
    public final void patchCallSite(int callOffset, Address callEntryPoint) {
        AMD64TargetMethodUtil.mtSafePatchCallDisplacement(this, codeStart().plus(callOffset), callEntryPoint.asAddress());
    }

    @Override
    public void forwardTo(TargetMethod newTargetMethod) {
        forwardTo(this, newTargetMethod);
    }

    @UNSAFE
    public static void forwardTo(TargetMethod oldTargetMethod, TargetMethod newTargetMethod) {
        AMD64TargetMethodUtil.forwardTo(oldTargetMethod, newTargetMethod);
    }

    @UNSAFE
    @Override
    public Address throwAddressToCatchAddress(boolean isTopFrame, Address throwAddress, Class<? extends Throwable> throwableClass) {
        final int exceptionPos = throwAddress.minus(codeStart).toInt();
        int count = getExceptionHandlerCount();
        for (int i = 0; i < count; i++) {
            int codePos = getExceptionPosAt(i);
            int catchPos = getCatchPosAt(i);
            ClassActor catchType = getCatchTypeAt(i);

            if (codePos == exceptionPos && checkType(throwableClass, catchType)) {
                return codeStart.plus(catchPos);
            }
        }
        return Address.zero();
    }

    private boolean checkType(Class<? extends Throwable> throwableClass, ClassActor catchType) {
        return catchType == null || catchType.isAssignableFrom(ClassActor.fromJava(throwableClass));
    }

    /**
     * Gets the exception code position of an entry in the exception handler table.
     *
     * @param i the index of the requested exception handler table entry
     * @return the exception code position of element {@code i} in the exception handler table
     */
    private int getExceptionPosAt(int i) {
        return exceptionPositionsToCatchPositions[i * 2];
    }

    /**
     * Gets the exception handler code position of an entry in the exception handler table.
     *
     * @param i the index of the requested exception handler table entry
     * @return the exception handler position of element {@code i} in the exception handler table
     */
    private int getCatchPosAt(int i) {
        return exceptionPositionsToCatchPositions[i * 2 + 1];
    }

    /**
     * Gets the exception type of an entry in the exception handler table.
     *
     * @param i the index of the requested exception handler table entry
     * @return the exception type of element {@code i} in the exception handler table
     */
    private ClassActor getCatchTypeAt(int i) {
        return exceptionClassActors[i];
    }

    /**
     * Gets the number of entries in the exception handler table.
     */
    private int getExceptionHandlerCount() {
        return exceptionClassActors == null ? 0 : exceptionClassActors.length;
    }

    @HOSTED_ONLY
    private void gatherInlinedMethods(Site site, Set<MethodActor> inlinedMethods) {
        CiDebugInfo debugInfo = site.debugInfo();
        if (debugInfo != null) {
            for (CiCodePos pos = debugInfo.codePos; pos != null; pos = pos.caller) {
                inlinedMethods.add((MethodActor) pos.method);
            }
        }
    }

    @Override
    @HOSTED_ONLY
    public void gatherCalls(Set<MethodActor> directCalls, Set<MethodActor> virtualCalls, Set<MethodActor> interfaceCalls, Set<MethodActor> inlinedMethods) {
        // first gather methods in the directCallees array
        if (directCallees != null) {
            for (Object o : directCallees) {
                if (o instanceof MethodActor) {
                    directCalls.add((MethodActor) o);
                }
            }
        }

        // iterate over direct calls
        for (CiTargetMethod.Call site : bootstrappingCiTargetMethod.directCalls) {
            if (site.runtimeCall != null) {
                directCalls.add(getClassMethodActor(site.runtimeCall, site.method));
            } else if (site.method != null) {
                MethodActor methodActor = (MethodActor) site.method;
                directCalls.add(methodActor);
            }
            gatherInlinedMethods(site, inlinedMethods);
        }

        // iterate over all the calls and append them to the appropriate lists
        for (CiTargetMethod.Call site : bootstrappingCiTargetMethod.indirectCalls) {
            if (site.method != null) {
                if (site.method.isResolved()) {
                    MethodActor methodActor = (MethodActor) site.method;
                    if (site.method.holder().isInterface()) {
                        interfaceCalls.add(methodActor);
                    } else {
                        virtualCalls.add(methodActor);
                    }
                }
            }
            gatherInlinedMethods(site, inlinedMethods);
        }
    }

    private ClassMethodActor getClassMethodActor(CiRuntimeCall runtimeCall, RiMethod method) {
        if (method != null) {
            return (ClassMethodActor) method;
        }

        assert runtimeCall != null : "A call can either be a call to a method or a runtime call";
        return C1XRuntimeCalls.getClassMethodActor(runtimeCall);
    }

    @Override
    public void traceDebugInfo(IndentWriter writer) {
    }

    @Override
    public void traceExceptionHandlers(IndentWriter writer) {
        if (getExceptionHandlerCount() != 0) {
            writer.println("Exception handlers:");
            writer.indent();
            for (int i = 0; i < getExceptionHandlerCount(); i++) {
                ClassActor catchType = getCatchTypeAt(i);
                writer.println((catchType == null ? "<any>" : catchType) + " @ " + getExceptionPosAt(i) + " -> " + getCatchPosAt(i));
            }
            writer.outdent();
        }
    }

    private static final boolean C1X_GENERATES_REG_REF_MAPS_AT_CALL_SITES = System.getProperty("C1X_GENERATES_REG_REF_MAPS_AT_CALL_SITES") != null;

    /**
     * Prepares the reference map for this frame.
     * @param current the current frame
     * @param callee the callee frame
     * @param preparer the reference map preparer
     */
    @Override
    public void prepareReferenceMap(Cursor current, Cursor callee, StackReferenceMapPreparer preparer) {
        StackFrameWalker.CalleeKind calleeKind = callee.calleeKind();
        Pointer registerState = Pointer.zero();
        CiCalleeSaveArea csa = null;
        switch (calleeKind) {
            case TRAMPOLINE:
                if (callee.targetMethod() instanceof C1XTargetMethod) {
                    if (C1X_GENERATES_REG_REF_MAPS_AT_CALL_SITES) {
                        // can simply use the register ref map at the call site
                        RiRegisterConfig registerConfig = vm().registerConfigs.trampoline;
                        csa = registerConfig.getCalleeSaveArea();
                        registerState = callee.sp();
                    } else {
                        prepareTrampolineRefMap(current, callee, preparer);
                    }
                } else {
                    // compute the register reference map from the call at this site
                    prepareTrampolineRefMap(current, callee, preparer);
                }
                break;
            case TRAP_STUB:  // fall through
                // get the register state from the callee's frame
                registerState = callee.sp();
                if (Trap.Number.isStackOverflow(registerState)) {
                    // a method can never catch stack overflow for itself
                    return;
                }
                break;
            case CALLEE_SAVED:
                if (callee.targetMethod() instanceof C1XTargetMethod) {
                    // can simply use the register ref map at the call site
                    C1XTargetMethod c1xCallee = (C1XTargetMethod) callee.targetMethod();
                    csa = c1xCallee.getRegisterConfig().getCalleeSaveArea();
                    registerState = callee.sp();
                } else {
                    // get the register state from the callee's frame
                    registerState = callee.sp();
                }
                break;
            case NATIVE:
                // no register state.
                break;
            case JAVA:
                // no register state.
                break;
        }
        int stopIndex = findClosestStopIndex(current.ip());
        if (stopIndex < 0) {
            // this is very bad.
            throw FatalError.unexpected("could not find stop index");
        }

        int frameReferenceMapSize = frameReferenceMapSize();
        if (!registerState.isZero()) {
            // the callee contains register state from this frame;
            // use register reference maps in this method to fill in the map for the callee
            Pointer slotPointer = registerState;
            int byteIndex = stopIndex * totalReferenceMapSize() + frameReferenceMapSize;
            preparer.tracePrepareReferenceMap(this, stopIndex, slotPointer, "C1X registers frame");
            if (csa != null) {
                // Need to translate from register numbers (as stored in the reg ref maps) to frame slots.
                for (int i = 0; i < registerReferenceMapSize(); i++) {
                    int b = referenceMaps[byteIndex] & 0xff;
                    int reg = i * 8;
                    while (b != 0) {
                        if ((b & 1) != 0) {
                            int offset = csa.offsetOf(reg);
                            if (Heap.traceRootScanning()) {
                                Log.print("    register: ");
                                Log.println(csa.registers[reg].name);
                            }
                            preparer.setReferenceMapBits(callee, slotPointer.plus(offset), 1, 1);
                        }
                        reg++;
                        b = b >>> 1;
                    }
                    byteIndex++;
                }
            } else {
                // The reg ref map already specified the frame slots in the callee for the registers
                for (int i = 0; i < registerReferenceMapSize(); i++) {
                    preparer.setReferenceMapBits(callee, slotPointer, referenceMaps[byteIndex] & 0xff, Bytes.WIDTH);
                    slotPointer = slotPointer.plusWords(Bytes.WIDTH);
                    byteIndex++;
                }
            }
        }

        // prepare the map for this stack frame
        Pointer slotPointer = current.sp();
        preparer.tracePrepareReferenceMap(this, stopIndex, slotPointer, "C1X stack frame");
        int byteIndex = stopIndex * totalReferenceMapSize();
        for (int i = 0; i < frameReferenceMapSize; i++) {
            preparer.setReferenceMapBits(current, slotPointer, referenceMaps[byteIndex] & 0xff, Bytes.WIDTH);
            slotPointer = slotPointer.plusWords(Bytes.WIDTH);
            byteIndex++;
        }
    }

    /**
     * Attempt to catch an exception that has been thrown with this method on the call stack.
     * @param current the current stack frame
     * @param callee the callee stack frame
     * @param throwable the exception being thrown
     */
    @Override
    public void catchException(Cursor current, Cursor callee, Throwable throwable) {
        AMD64OptStackWalking.catchException(this, current, callee, throwable);
    }

    /**
     * Accept a visitor for this frame.
     * @param current the current stack frame
     * @param visitor the visitor
     * @return {@code true} if the stack walker should continue walking, {@code false} if the visitor is finished visiting
     */
    @Override
    public boolean acceptStackFrameVisitor(Cursor current, StackFrameVisitor visitor) {
        return AMD64OptStackWalking.acceptStackFrameVisitor(current, visitor);
    }

    /**
     * Advances the cursor to the caller's frame.
     * @param current the current frame
     */
    @Override
    public void advance(Cursor current) {
        AMD64OptStackWalking.advance(current);
    }

    @Override
    public CiDebugInfo getDebugInfo(Pointer ip, boolean implicitExceptionPoint) {
        if (!implicitExceptionPoint && Platform.platform().isa.offsetToReturnPC == 0) {
            ip = ip.minus(1);
        }

        int stopIndex = findClosestStopIndex(ip);
        if (stopIndex < 0) {
            return null;
        }
        return decodeDebugInfo(classMethodActor, sourceInfo, sourceMethods, stopIndex);
    }

    @Override
    public BytecodeLocation getBytecodeLocationFor(Pointer ip, boolean implicitExceptionPoint) {
        if (!implicitExceptionPoint && Platform.platform().isa.offsetToReturnPC == 0) {
            ip = ip.minus(1);
        }

        int stopIndex = findClosestStopIndex(ip);
        if (stopIndex < 0) {
            return null;
        }
        return decodeBytecodeLocation(classMethodActor, sourceInfo, sourceMethods, stopIndex);
    }

    @Override
    public BytecodeLocation getBytecodeLocationFor(int stopIndex) {
        if (classMethodActor == null) {
            return null;
        }
        return decodeBytecodeLocation(classMethodActor, sourceInfo, sourceMethods, stopIndex);
    }

    public static BytecodeLocation decodeBytecodeLocation(ClassMethodActor classMethodActor, Object sourceInfoObject, ClassMethodActor[] sourceMethods, int index) {
        if (sourceInfoObject instanceof int[]) {
            int[] sourceInfo = (int[]) sourceInfoObject;
            if (index < 0) {
                return null;
            }
            int start = index * 3;
            ClassMethodActor sourceMethod = sourceMethods[sourceInfo[start]];
            int bci = sourceInfo[start + 1];
            int parentIndex = sourceInfo[start + 2];
            final BytecodeLocation parent = decodeBytecodeLocation(classMethodActor, sourceInfo, sourceMethods, parentIndex);
            return new BytecodeLocation(sourceMethod, bci) {
                @Override
                public BytecodeLocation parent() {
                    return parent;
                }
            };
        } else if (sourceInfoObject instanceof char[]) {
            // no inlined methods; just recover the bytecode index
            char[] array = (char[]) sourceInfoObject;
            return new BytecodeLocation(classMethodActor, array[index]);
        } else {
            return null;
        }

    }

    public static CiDebugInfo decodeDebugInfo(ClassMethodActor classMethodActor, Object sourceInfoObject, ClassMethodActor[] sourceMethods, int index) {
        if (sourceInfoObject instanceof int[]) {
            int[] sourceInfo = (int[]) sourceInfoObject;
            if (index < 0) {
                return null;
            }
            int start = index * 3;
            ClassMethodActor sourceMethod = sourceMethods[sourceInfo[start]];
            int bci = sourceInfo[start + 1];
            int parentIndex = sourceInfo[start + 2];
            final CiDebugInfo caller = decodeDebugInfo(classMethodActor, sourceInfo, sourceMethods, parentIndex);
            CiCodePos callerPos = caller == null ? null : caller.codePos;
            return new CiDebugInfo(new CiCodePos(callerPos, sourceMethod, bci), null, null);
        } else if (sourceInfoObject instanceof char[]) {
            // no inlined methods; just recover the bytecode index
            char[] array = (char[]) sourceInfoObject;
            return new CiDebugInfo(new CiCodePos(null, classMethodActor, array[index]), null, null);
        } else {
            return null;
        }
    }
}