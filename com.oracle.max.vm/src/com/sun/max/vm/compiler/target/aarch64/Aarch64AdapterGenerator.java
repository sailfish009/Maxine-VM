/*
 * Copyright (c) 2018, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
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
 */
package com.sun.max.vm.compiler.target.aarch64;

import static com.sun.cri.ci.CiCallingConvention.Type.*;
import static com.sun.max.platform.Platform.*;
import static com.sun.max.vm.compiler.deopt.Deoptimization.*;
import static com.sun.max.vm.compiler.target.aarch64.Aarch64TargetMethodUtil.*;

import java.io.*;

import com.oracle.max.asm.*;
import com.oracle.max.asm.target.aarch64.*;
import com.oracle.max.cri.intrinsics.*;
import com.sun.cri.ci.*;
import com.sun.max.annotate.*;
import com.sun.max.lang.*;
import com.sun.max.platform.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.collect.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.compiler.target.Adapter.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.stack.*;
import com.sun.max.vm.type.*;


/**
 * Adapters for Aarch64 baseline and optimised stack frames.
 *
 */
public abstract class Aarch64AdapterGenerator extends AdapterGenerator {

    final CiRegister scratch;

    static {
        if (MaxineVM.vm().compilationBroker.needsAdapters()) {
            new Baseline2Opt();
            new Opt2Baseline();
        }
    }


    public Aarch64AdapterGenerator(Adapter.Type adapterType) {
        super(adapterType);
        scratch = opt.getScratchRegister();
    }

    public static class Baseline2Opt extends Aarch64AdapterGenerator {

        public Baseline2Opt() {
            super(Adapter.Type.BASELINE2OPT);
        }

        public static class Baseline2OptAdapter extends Adapter {

            public Baseline2OptAdapter(AdapterGenerator generator, String description, int frameSize, byte[] code, int callPos, int callSize) {
                super(generator, description, frameSize, code, callPos, callSize);
            }

            /**
             * See {@link Baseline2Opt#emitPrologue(Object, Adapter)}.
             */
            @Override
            public int callOffsetInPrologue() {
                return 4;
            }

            @Override
            public int callSizeInPrologue() {
                return RIP_CALL_INSTRUCTION_SIZE;
            }

            @HOSTED_ONLY
            private int computeFrameState(StackFrameCursor cursor) {
                assert false : "Not currently implemented on Aarch64";
                return 0;
            }

            @Override
            @HOSTED_ONLY
            public boolean acceptStackFrameVisitor(StackFrameCursor current, StackFrameVisitor visitor) {
                int ripAdjustment = computeFrameState(current) & ~1;
                Pointer ripPointer = current.sp().plus(ripAdjustment);
                Pointer fp = ripPointer.minus(frameSize());
                return visitor.visitFrame(new AdapterStackFrame(current.stackFrameWalker().calleeStackFrame(), current.targetMethod(), current.vmIP().toPointer(), fp, current.sp()));
            }

            @Override
            public void advance(StackFrameCursor current) {
                StackFrameWalker sfw = current.stackFrameWalker();
                int ripAdjustment = frameSize();
                boolean rbpSaved = true;
                if (MaxineVM.isHosted()) {
                    // Inspector context only
                    int state = computeFrameState(current);
                    ripAdjustment = state & ~1;
                    rbpSaved = (state & 1) == 1;
                }

                Pointer ripPointer = current.sp().plus(ripAdjustment);
                Pointer callerIP = sfw.readWord(ripPointer, 0).asPointer();
                Pointer callerSP = ripPointer.plus(Word.size()); // Skip RIP word
                Pointer callerFP = rbpSaved ? sfw.readWord(ripPointer, -Word.size() * 2).asPointer() : current.fp();

                boolean wasDisabled = SafepointPoll.disable();
                sfw.advance(callerIP, callerSP, callerFP);
                if (!wasDisabled) {
                    SafepointPoll.enable();
                }

            }

            @Override
            public Pointer returnAddressPointer(StackFrameCursor frame) {
                int ripAdjustment = frameSize();
                if (MaxineVM.isHosted()) {
                    // Inspector context only
                    int state = computeFrameState(frame);
                    ripAdjustment = state & ~1;
                }

                return frame.sp().plus(ripAdjustment);
            }

            @Override
            public VMFrameLayout frameLayout() {
                return new Baseline2OptAdapterFrameLayout(frameSize());
            }

        }

        public static class Baseline2OptAdapterWithBigRefMap extends Baseline2OptAdapter {

            final byte[] refMap;

            public Baseline2OptAdapterWithBigRefMap(AdapterGenerator generator, String description, byte[] refMap, int frameSize, byte[] code, int callPos, int callSize) {
                super(generator, description, frameSize, code, callPos, callSize);
                this.refMap = refMap;
            }

            @Override
            public void prepareReferenceMap(StackFrameCursor current, StackFrameCursor callee, FrameReferenceMapVisitor preparer) {
                preparer.logPrepareReferenceMap(this, 0, current.sp(), "frame");
                int frameSlotIndex = preparer.referenceMapBitIndex(current.sp());
                int byteIndex = 0;
                for (int i = 0; i < refMap.length; i++) {
                    final byte frameReferenceMapByte = refMap[byteIndex];
                    preparer.logReferenceMapByteBefore(byteIndex, frameReferenceMapByte, "Frame");
                    preparer.setBits(frameSlotIndex, frameReferenceMapByte);
                    preparer.logReferenceMapByteAfter(current.sp(), frameSlotIndex, frameReferenceMapByte);
                    frameSlotIndex += Bytes.WIDTH;
                    byteIndex++;
                }
            }
        }

        public static class Baseline2OptAdapterWithRefMap extends Baseline2OptAdapter {

            final long refMap;

            public Baseline2OptAdapterWithRefMap(AdapterGenerator generator, String description, long refMap, int frameSize, byte[] code, int callPos, int callSize) {
                super(generator, description, frameSize, code, callPos, callSize);
                this.refMap = refMap;
            }

            @Override
            public void prepareReferenceMap(StackFrameCursor current, StackFrameCursor callee, FrameReferenceMapVisitor preparer) {
                preparer.logPrepareReferenceMap(this, 0, current.sp(), "frame");
                int frameSlotIndex = preparer.referenceMapBitIndex(current.sp());
                int byteIndex = 0;
                long refMap = this.refMap;
                for (int i = 0; i < 8; i++) {
                    final byte refMapByte = (byte) (refMap & 0xff);
                    preparer.logReferenceMapByteBefore(byteIndex, refMapByte, "Frame");
                    preparer.setBits(frameSlotIndex, refMapByte);
                    preparer.logReferenceMapByteAfter(current.sp(), frameSlotIndex, refMapByte);
                    refMap >>>= Bytes.WIDTH;
                    frameSlotIndex += Bytes.WIDTH;
                    byteIndex++;
                }
            }

        }
        /**
         * Frame layout for an Aarch64 specific {@link Type#BASELINE2OPT} adapter frame.
         * <pre>
         *
         *          +------------------------+
         *          |  Baseline caller RIP   |
         *          +------------------------+     ---
         *          |  callers frame pointer |      ^
         *          +------------------------+      |
         *          |        OPT arg N       |      |
         *          +------------------------+      |
         *          :                        : frame size
         *          +------------------------+      |
         *          |        OPT arg S0      |      |
         *          +------------------------+      |
         *    SP--> |    deopt rescue slot   |      v
         *          +------------------------+     ---
         *
         *    N == number of args - 1
         *   S0 == first stack arg (S0 == number of register args)
         * </pre>
         */
        public static class Baseline2OptAdapterFrameLayout extends AdapterStackFrameLayout {

            public Baseline2OptAdapterFrameLayout(int frameSize) {
                super(frameSize, true);
            }

            @Override
            public CiRegister framePointerReg() {
                return Aarch64.sp;
            }

            @Override
            public int frameReferenceMapOffset() {
                return 0;
            }

            @Override
            public int frameReferenceMapSize() {
                return ByteArrayBitMap.computeBitMapSize(UnsignedMath.divide(frameSize(), STACK_SLOT_SIZE));
            }

            @Override
            public Slots slots() {
                return new Slots() {
                    @Override
                    protected String nameOfSlot(int offset) {
                        final int offsetOfReturnAddress = frameSize();
                        if (offset == DEOPT_RETURN_ADDRESS_OFFSET) {
                            return "deopt rescue";
                        }
                        if (offset == offsetOfReturnAddress) {
                            return "return address";
                        }

                        final int callersRBPOffset = offsetOfReturnAddress - Word.size();
                        if (offset == callersRBPOffset) {
                            return "caller's FP";
                        }
                        return "stack arg " + (offset / Word.size() - 1);
                    }
                };
            }
        }

        /**
         * The size in bytes of the prologue, see {@link Baseline2Opt#emitPrologue(Object, Adapter)}.
         */
        private static final int PROLOGUE_SIZE = 8;

        @Override
        public int prologueSizeForCallee(ClassMethodActor callee) {
            return PROLOGUE_SIZE;
        }

        /**
         * The prologue for a method with a BASELINE2OPT pushes the link register onto the stack
         * at the {@link CallEntryPoint#BASELINE_ENTRY_POINT} and branches to the adapter.
         * The body of the method starts at the {@link CallEntryPoint#OPTIMIZED_ENTRY_POINT}.
         * The assembler code is as follows:
         * <pre>
         *     +0:  str lr, [sp,#-16]!
         *     +4:  bl <adapter>
         *     +8:  optimised method body
         * </pre>
         */
        @Override
        protected int emitPrologue(Object out, Adapter adapter) {
            Aarch64MacroAssembler masm = out instanceof OutputStream ? new Aarch64MacroAssembler(Platform.target(), null) : (Aarch64MacroAssembler) out;
            if (adapter == null) {
                masm.nop(2);
            } else {
                masm.push(64, Aarch64.linkRegister);
                masm.call();
            }
            int size = masm.codeBuffer.position();
            assert size == PROLOGUE_SIZE;
            copyIfOutputStream(masm.codeBuffer, out);
            return size;
        }

        @Override
        protected Adapter create(Sig sig) {
            CiValue[] optArgs = opt.getCallingConvention(JavaCall, WordUtil.ciKinds(sig.kinds, true), target(), false).locations;
            Aarch64MacroAssembler masm = new Aarch64MacroAssembler(Platform.target(), null);
            // Compute the number of stack args needed for the call (i.e. the args that won't
            // be put into registers)
            int stackArgumentsSize = 0;
            for (int i = optArgs.length - 1; i >= 0; i--) {
                if (optArgs[i].isStackSlot()) {
                    CiStackSlot slot = (CiStackSlot) optArgs[i];
                    int offset = slot.index() * OPT_SLOT_SIZE;
                    int end = offset + OPT_SLOT_SIZE;
                    if (end > stackArgumentsSize) {
                        stackArgumentsSize = end;
                    }
                }
            }
            final int rbpSlotSize = OPT_SLOT_SIZE;
            final int baselineCallerRIPSlotSize = OPT_SLOT_SIZE;
            final int implicitlyAllocatedFrameSize = baselineCallerRIPSlotSize + rbpSlotSize;
            int adapterFrameSize = target().alignFrameSize(stackArgumentsSize + implicitlyAllocatedFrameSize - baselineCallerRIPSlotSize);
            // The amount by which RSP must be explicitly adjusted to create the adapter frame
            final int explicitlyAllocatedFrameSize = adapterFrameSize - rbpSlotSize;
            assert explicitlyAllocatedFrameSize >= 0 && explicitlyAllocatedFrameSize <= Short.MAX_VALUE;

            // stack the baseline caller's frame pointer
            masm.push(64, Aarch64.fp);

            // the adapter frame pointer = the current stack pointer
            masm.mov(64, Aarch64.fp, Aarch64.sp);

            // allocate the adapter frame
            masm.sub(64, Aarch64.sp, Aarch64.sp, explicitlyAllocatedFrameSize);

            // At this point, the top of the baseline caller's stack (i.e the last arg to the call) is immediately
            // above the adapter's RIP slot. That is, it's at RSP + adapterFrameSize + OPT_SLOT_SIZE.
            int baselineStackOffset = adapterFrameSize + OPT_SLOT_SIZE;
            int baselineArgsSize = 0;
            CiBitMap refMap = null;
            for (int i = optArgs.length - 1; i >= 0;  i--) {
                Kind kind = sig.kinds[i];

                if (!kind.isCategory1) {
                    // Skip over the second slot of a long or double
                    baselineStackOffset += BASELINE_SLOT_SIZE;
                    baselineArgsSize += BASELINE_SLOT_SIZE;
                }

                int refMapIndex = adaptArgument(masm, kind, optArgs[i], baselineStackOffset, 0);
                if (refMapIndex != -1) {
                    if (refMap == null) {
                        refMap = new CiBitMap(adapterFrameSize / Word.size());
                    }
                    refMap.set(refMapIndex);
                }
                baselineArgsSize += BASELINE_SLOT_SIZE;
                baselineStackOffset += BASELINE_SLOT_SIZE;
            }

            // Args are now copied to the OPT locations; call the OPT main body
            int callPos = masm.codeBuffer.position();

            /* The adapter is called from the prologue -- the link register contains
             * the address of the optimised callees method body.
             */
            masm.blr(Aarch64.linkRegister);
            int callSize = masm.codeBuffer.position() - callPos;
            // restore the stack pointer,
            masm.mov(64, Aarch64.sp, Aarch64.fp);

            // and the caller's frame pointer,
            masm.pop(64, Aarch64.fp);

            // and the baseline return address.
            masm.pop(64, Aarch64.linkRegister);

            // roll the stack pointer back before the first argument on the caller's stack.
            masm.add(64, Aarch64.sp, Aarch64.sp, baselineArgsSize);
            masm.ret(Aarch64.linkRegister);

            final byte[] code = masm.codeBuffer.close(true);

            String description = Type.BASELINE2OPT + "-Adapter" + sig;
            if (refMap != null) {
                if (refMap.size() <= 64) {
                    long longRefMap = refMap.toLong();
                    return new Baseline2OptAdapterWithRefMap(this, description, longRefMap, adapterFrameSize, code, callPos, callSize);
                }
                return new Baseline2OptAdapterWithBigRefMap(this, description, refMap.toByteArray(), adapterFrameSize, code, callPos, callSize);
            }
            return new Baseline2OptAdapter(this, description, adapterFrameSize, code, callPos, callSize);
        }

    }


    /**
     *
     *
     *
     */
    public static class Opt2Baseline extends Aarch64AdapterGenerator {

        static final int PROLOGUE_SIZE = 16;
        static final int PROLOGUE_SIZE_FOR_NO_ARGS_CALLEE = 8;

        public Opt2Baseline() {
            super(Adapter.Type.OPT2BASELINE);
        }

        static final class Opt2BaselineAdapter extends Adapter {

            /**
             * The offset in the prologue of the call to the adapter.
             */
            private static final int CALL_OFFSET_IN_PROLOGUE = +12;

            Opt2BaselineAdapter(AdapterGenerator generator, String description, int frameSize, byte[] code, int callPos, int callSize) {
                super(generator, description, frameSize, code, callPos, callSize);
            }

            @Override
            public int callOffsetInPrologue() {
                return CALL_OFFSET_IN_PROLOGUE;
            }

            @Override
            public int callSizeInPrologue() {
                return RIP_CALL_INSTRUCTION_SIZE;
            }

            /**
             * See comments in AMD64AdapterGenerator.
             * @param walker
             * @return
             */
            @HOSTED_ONLY
            private int computeRipAdjustment(StackFrameCursor cursor) {
                assert false : "Not yet implemented in Aarch64";
                return 0;
            }

            @Override
            @HOSTED_ONLY
            public boolean acceptStackFrameVisitor(StackFrameCursor cursor, StackFrameVisitor visitor) {
                int ripAdjustment = MaxineVM.isHosted() ? computeRipAdjustment(cursor) : Word.size();

                Pointer ripPointer = cursor.sp().plus(ripAdjustment);
                Pointer fp = ripPointer.minus(frameSize());
                return visitor.visitFrame(new AdapterStackFrame(cursor.stackFrameWalker().calleeStackFrame(), cursor.targetMethod(), cursor.vmIP().toPointer(), fp, cursor.sp()));
            }

            @Override
            public void advance(StackFrameCursor cursor) {
                int ripAdjustment = MaxineVM.isHosted() ? computeRipAdjustment(cursor) : Word.size();
                StackFrameWalker sfw = cursor.stackFrameWalker();

                Pointer ripPointer = cursor.sp().plus(ripAdjustment);
                Pointer callerIP = sfw.readWord(ripPointer, 0).asPointer();
                Pointer callerSP = ripPointer.plus(Word.size()); // Skip RIP word
                Pointer callerFP = cursor.fp();

                boolean wasDisabled = SafepointPoll.disable();
                sfw.advance(callerIP, callerSP, callerFP);
                if (!wasDisabled) {
                    SafepointPoll.enable();
                }

            }

            @Override
            public Pointer returnAddressPointer(StackFrameCursor frame) {
                int ripAdjustment = MaxineVM.isHosted() ? computeRipAdjustment(frame) : Word.size();
                return frame.sp().plus(ripAdjustment);
            }

            @Override
            public VMFrameLayout frameLayout() {
                return new Opt2BaselineAdapterFrameLayout(frameSize());
            }

        }


        /**
         * Frame layout for an Aarch64 specific {@link Type#OPT2BASELINE} adapter frame.
         * <pre>
         *
         *          +------------------------+
         *          |     OPT caller RIP     |
         *          +------------------------+     ---
         *          |    baseline arg 0      |      ^
         *          +------------------------+      |
         *          :                        : frame size
         *          +------------------------+      |
         *    SP--> |    baseline arg N      |      v
         *          +------------------------+     ---
         *
         *   N == number of args - 1
         * </pre>
         */
        public static class Opt2BaselineAdapterFrameLayout extends AdapterStackFrameLayout {

            public Opt2BaselineAdapterFrameLayout(int frameSize) {
                super(frameSize, true);
            }

            @Override
            public CiRegister framePointerReg() {
                return Aarch64.sp;
            }

            @Override
            public Slots slots() {
                return new Slots() {
                    @Override
                    protected String nameOfSlot(int offset) {
                        final int baselinePrologueCallReturnAddress = frameSize() - Word.size();
                        if (offset == baselinePrologueCallReturnAddress) {
                            return "prologue return";
                        }
                        return super.nameOfSlot(offset);
                    }
                };
            }
        }

        @Override
        public int prologueSizeForCallee(ClassMethodActor callee) {
            if (callee.descriptor().numberOfParameters() == 0 && callee.isStatic()) {
                return PROLOGUE_SIZE_FOR_NO_ARGS_CALLEE;
            }
            return PROLOGUE_SIZE;
        }

        /**
         *
         *
         * @param out
         * @param adapter
         * @return
         * No Adaptation:
         * <pre>
         *      +0: nop                 <- baseline entry point
         *      +4: nop
         *      +8: method body         <- optimised e.p.
         * </pre>
         *
         * With Adaptation:
         * <pre>
         *      +0: b   L1              <- baseline e.p.
         *      +4: nop
         *      +8: str lr, [sp,#-16]!  <- optimised e.p.
         *     +12: bl  <adapter>
         * L1  +16: method body
         * </pre>
         *
         */
        @Override
        protected int emitPrologue(Object out, Adapter adapter) {
            Aarch64MacroAssembler masm = out instanceof OutputStream ? new Aarch64MacroAssembler(Platform.target(), null) : (Aarch64MacroAssembler) out;
            if (adapter == null) {
                masm.nop(CallEntryPoint.OPTIMIZED_ENTRY_POINT.offset());
                assert masm.codeBuffer.position() == PROLOGUE_SIZE_FOR_NO_ARGS_CALLEE;
                copyIfOutputStream(masm.codeBuffer, out);
                return PROLOGUE_SIZE_FOR_NO_ARGS_CALLEE;
            }
            Label end = new Label();
            masm.b(end);
            masm.nop();
            // stack the return address in the caller, i.e. the instruction following the branch to
            // here in the optimised caller.
            masm.push(64, Aarch64.linkRegister);
            masm.bl(0);
            masm.bind(end);
            int size = masm.codeBuffer.position();
            assert size == PROLOGUE_SIZE : "Bad prologue";
            copyIfOutputStream(masm.codeBuffer, out);
            return size;
        }

        /*
         * (non-Javadoc)
         * @see com.sun.max.vm.compiler.target.aarch64.Aarch64AdapterGenerator#create(com.sun.max.vm.compiler.target.AdapterGenerator.Sig)
         */
        @Override
        protected Adapter create(Sig sig) {
            CiValue[] optArgs = opt.getCallingConvention(JavaCall, WordUtil.ciKinds(sig.kinds, true), target(), false).locations;
            Aarch64MacroAssembler masm = new Aarch64MacroAssembler(Platform.target(), null);
            final int baselineArgsSize = frameSizeFor(sig.kinds, BASELINE_SLOT_SIZE);
            assert baselineArgsSize % Platform.target().stackAlignment == 0 : "Bad stack alignment";
            /* XXX Review this
             */
            final int optCallerRIPSlotSize = OPT_SLOT_SIZE;
            final int adapterFrameSize = baselineArgsSize + optCallerRIPSlotSize;

            // adjust stack pointer to accommodate baseline args
            masm.sub(64, Aarch64.sp, Aarch64.sp, baselineArgsSize);


            int baselineStackOffset = 0;

            for (int i = optArgs.length - 1; i >= 0; i--) {
                Kind argKind = sig.kinds[i];
                if (!argKind.isCategory1) {
                    // Skip over the second slot of a long or double
                    baselineStackOffset += BASELINE_SLOT_SIZE;
                }
                adaptArgument(masm, argKind, optArgs[i], baselineStackOffset, adapterFrameSize);
                baselineStackOffset += BASELINE_SLOT_SIZE;
            }
            int callPos = masm.codeBuffer.position();
            // The branch to this adapter is from the method prologue, the link register
            // contains the address of the baseline method body, go there.
            masm.blr(Aarch64.linkRegister);
            int callSize = masm.codeBuffer.position() - callPos;
            masm.pop(64, Aarch64.linkRegister);
            masm.ret(Aarch64.linkRegister);
            final byte [] code = masm.codeBuffer.close(true);
            String description = Type.OPT2BASELINE + "-Adapter" + sig;
            return new Opt2BaselineAdapter(this, description, adapterFrameSize, code, callPos, callSize);
        }
    }

    private static void adapt(Aarch64MacroAssembler masm, Kind kind, CiRegister reg, int offset32) {
        // Checkstyle: stop
        switch(kind.asEnum) {
            case BYTE:
            case BOOLEAN:
            case SHORT:
            case CHAR:
            case INT:
                masm.str(32, reg, Aarch64Address.createUnscaledImmediateAddress(Aarch64.sp, offset32));
                break;
            case WORD:
            case REFERENCE:
            case LONG:
                masm.str(64, reg, Aarch64Address.createUnscaledImmediateAddress(Aarch64.sp, offset32));
                break;
            case FLOAT:
                masm.fstr(32, reg, Aarch64Address.createUnscaledImmediateAddress(Aarch64.sp, offset32));
                break;
            case DOUBLE:
                masm.fstr(64, reg, Aarch64Address.createUnscaledImmediateAddress(Aarch64.sp, offset32));
                break;
            default :
                throw ProgramError.unexpected("Bad case");
        }
        // Checkstyle: resume

    }

    /**
     * Transfer a java parameter from an optimised caller to a baseline method callee. The common case is
     * register -> stack, however and spilled parameters will be copied from the callers stack frame
     * to the callees.
     *
     * @param asm
     * @param kind
     * @param optArg
     * @param baselineStackOffset32
     * @param adapterFrameSize
     * @return
     */
    protected int adaptArgument(Aarch64MacroAssembler asm, Kind kind, CiValue optArg, int baselineStackOffset32, int adapterFrameSize) {
        if (optArg.isRegister()) {
            adapt(asm, kind, optArg.asRegister(), baselineStackOffset32);
        } else if (optArg.isStackSlot()) {
            int optStackOffset32 = ((CiStackSlot) optArg).index() * OPT_SLOT_SIZE;
            adapt(asm, kind, optStackOffset32, baselineStackOffset32, adapterFrameSize);
            if (kind.isReference) {
                return optStackOffset32 / Word.size();
            }
        } else {
            throw FatalError.unexpected("Unadaptable parameter location type: " + optArg.getClass().getSimpleName());
        }
        return -1;
    }

    /**
     * Adapt a parameter from the callers stack frame to the callees stack frame.
     * @param asm
     * @param kind
     * @param optStackOffset32
     * @param baselineStackOffset32
     * @param adapterFrameSize
     */
    private void adapt(Aarch64MacroAssembler asm, Kind kind, int optStackOffset32, int baselineStackOffset32, int adapterFrameSize) {
        int src = adapterFrameSize + optStackOffset32 + Word.size();
        int dst = baselineStackOffset32;
        stackCopy(asm, kind, src, dst);
    }

    /**
     * Copy one of the optimised callers arguments which has been spilled to the stack to the position
     * in the callees stack frame.
     * @param asm
     * @param kind
     * @param sourceStackOffset
     * @param destStackOffset
     */
    private void stackCopy(Aarch64MacroAssembler asm, Kind kind, int sourceStackOffset, int destStackOffset) {
        if (kind.width == WordWidth.BITS_64) {
            asm.ldr(64, scratch, Aarch64Address.createUnscaledImmediateAddress(Aarch64.sp, sourceStackOffset));
            asm.str(64, scratch, Aarch64Address.createUnscaledImmediateAddress(Aarch64.sp, destStackOffset));
        } else {
            asm.ldr(32, scratch, Aarch64Address.createUnscaledImmediateAddress(Aarch64.sp, sourceStackOffset));
            asm.str(32, scratch, Aarch64Address.createUnscaledImmediateAddress(Aarch64.sp, destStackOffset));
        }
    }

}
