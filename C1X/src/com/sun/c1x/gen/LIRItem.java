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
package com.sun.c1x.gen;

import com.sun.c1x.alloc.OperandPool.*;
import com.sun.c1x.ir.*;
import com.sun.c1x.util.*;
import com.sun.cri.ci.*;

/**
 * A helper utility for loading the {@linkplain Value#operand() result}
 * of an instruction for use by another instruction. This helper takes
 * into account the specifics of the consuming instruction such as whether
 * it requires the input operand to be in memory or a register, any
 * register size requirements of the input operand, and whether the
 * usage has the side-effect of overwriting the input operand. To satisfy
 * these constraints, an intermediate operand may be created and move
 * instruction inserted to copy the output of the producer instruction
 * into the intermediate operand.
 *
 * @author Marcelo Cintra
 * @author Thomas Wuerthinger
 * @author Doug Simon
 */
public class LIRItem {

    /**
     * The instruction whose usage by another instruction is being modeled by this object.
     * An instruction {@code x} uses instruction {@code y} if the {@linkplain Value#operand() result}
     * of {@code y} is an input operand of {@code x}.
     */
    public Value instruction;

    /**
     * The LIR context of this helper object.
     */
    private final LIRGenerator gen;

    /**
     * The operand holding the result of this item's {@linkplain #instruction}.
     */
    private CiValue resultOperand;

    /**
     * Denotes if the use of the instruction's {@linkplain #resultOperand result operand}
     * overwrites the value in the operand. That is, the use both uses and defines the
     * operand. In this case, an {@linkplain #intermediateOperand intermediate operand}
     * is created for the use so that other consumers of this item's {@linkplain #instruction}
     * are not impacted.
     */
    private boolean destructive;

    /**
     * @see #destructive
     */
    private CiValue intermediateOperand;

    public LIRItem(Value value, LIRGenerator gen) {
        this.gen = gen;
        setInstruction(value);
    }

    public void setInstruction(Value instruction) {
        this.instruction = instruction;
        if (instruction != null) {
            resultOperand = gen.makeOperand(instruction);
        } else {
            resultOperand = CiValue.IllegalValue;
        }
        intermediateOperand = CiValue.IllegalValue;
    }

    public LIRItem(LIRGenerator gen) {
        this.gen = gen;
        setInstruction(null);
    }

    private CiKind nonWordKind(CiKind kind) {
        if (kind.isWord()) {
            return gen.is64 ? CiKind.Long : CiKind.Int;
        }
        return kind;
    }

    /**
     * Forces the result of this item's {@linkplain #instruction} to be available in a given operand,
     * inserting move instructions if necessary.
     *
     * @param instruction an instruction that produces a {@linkplain Value#operand() result}
     * @param operand the operand in which the result of {@code instruction} must be available
     * @return {@code operand}
     */
    public void loadItemForce(CiValue operand) {
        CiValue result = result();
        if (result != operand) {
            assert result.kind != CiKind.Illegal;
            if (nonWordKind(result.kind) != nonWordKind(operand.kind)) {
                // moves between different types need an intervening spill slot
                CiValue tmp = gen.forceToSpill(result, operand.kind, false);
                gen.lir.move(tmp, operand);
            } else {
                gen.lir.move(result, operand);
            }
            resultOperand = operand;
        }
    }

    public void loadItem(CiKind kind) {
        if (kind == CiKind.Byte || kind == CiKind.Boolean) {
            loadByteItem();
        } else {
            loadItem();
        }
    }

    public void loadForStore(CiKind kind) {
        if (gen.canStoreAsConstant(instruction, kind)) {
            resultOperand = instruction.operand();
            if (!resultOperand.isConstant()) {
                resultOperand = instruction.asConstant();
            }
        } else if (kind == CiKind.Byte || kind == CiKind.Boolean) {
            loadByteItem();
        } else {
            loadItem();
        }
    }

    public CiValue result() {
        assert !destructive || resultOperand.isVariable() : "shouldn't use setDestroysRegister with physical registers";
        if (destructive && resultOperand.isVariable()) {
            if (intermediateOperand.isIllegal()) {
                intermediateOperand = gen.newVariable(instruction.kind);
                gen.lir.move(resultOperand, intermediateOperand);
            }
            return intermediateOperand;
        } else {
            return resultOperand;
        }
    }

    public void setDestroysRegister() {
        destructive = true;
    }

    /**
     * Determines if the operand is in a stack slot.
     */
    public boolean isStack() {
        return resultOperand.isAddress() || resultOperand.isStackSlot();
    }

    /**
     * Determines if the operand is in a register or may be
     * resolved to a register by the register allocator.
     */
    public boolean isRegisterOrVariable() {
        return resultOperand.isVariableOrRegister();
    }

    public void loadByteItem() {
        if (gen.compilation.target.arch.isX86()) {
            loadItem();
            CiValue res = result();

            if (!res.isVariable() || !gen.operands.mustBeByteRegister(res)) {
                // make sure that it is a byte register
                assert !instruction.kind.isFloat() && !instruction.kind.isDouble() : "can't load floats in byte register";
                CiValue reg = gen.operands.newVariable(CiKind.Byte, VariableFlag.MustBeByteRegister);
                gen.lir.move(res, reg);
                resultOperand = reg;
            }
        } else if (gen.compilation.target.arch.isSPARC()) {
            loadItem();
        } else {
            Util.shouldNotReachHere();
        }
    }

    public void loadNonconstant() {
        if (gen.compilation.target.arch.isX86()) {
            CiValue r = instruction.operand();
            if (r.isConstant()) {
                resultOperand = r;
            } else {
                loadItem();
            }
        } else if (gen.compilation.target.arch.isSPARC()) {
            CiValue r = instruction.operand();
            if (gen.canInlineAsConstant(instruction)) {
                if (!r.isConstant()) {
                    r = instruction.asConstant();
                }
                resultOperand = r;
            } else {
                loadItem();
            }
        } else {
            Util.shouldNotReachHere();
        }
    }

    void setResult(CiVariable operand) {
        gen.setResult(instruction, operand);
        resultOperand = operand;
    }

    /**
     * Creates an operand containing the result of {@linkplain #instruction input instruction}.
     */
    public void loadItem() {
        if (result().isIllegal()) {
            // update the item's result
            resultOperand = instruction.operand();
        }
        CiValue result = result();
        if (!result.isVariableOrRegister()) {
            CiVariable operand;
            operand = gen.newVariable(instruction.kind);
            gen.lir.move(result, operand);
            if (result.isConstant()) {
                resultOperand = operand;
            } else {
                setResult(operand);
            }
        }
    }

    public int asInt() {
        assert instruction instanceof Constant : "must be a constant";
        return instruction.asConstant().asInt();
    }

    public long asLong() {
        assert instruction instanceof Constant : "must be a constant";
        return instruction.asConstant().asLong();
    }

    @Override
    public String toString() {
        return result().toString();
    }
}
