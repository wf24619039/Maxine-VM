package com.oracle.max.asm.target.armv7;

import com.oracle.max.asm.target.armv7.ARMV7Assembler.*;
import com.sun.cri.ci.*;

public class ARMV7Instrumentation {

    private ARMV7Assembler asm;
    private static int maxineIntrumentationBufferAddress = 0;

    public static boolean ENABLED = false;

    public ARMV7Instrumentation(ARMV7Assembler asm) {
        this.asm = asm;

    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void setInstrumentationBufferAddress(int address) {
        assert maxineIntrumentationBufferAddress == 0 : "Re-initialization of instrumentation buffer!";
        if (maxineIntrumentationBufferAddress == 0) {
            maxineIntrumentationBufferAddress = address;
        }
    }

    public int getInstrumentationBufferAddress() {
        assert maxineIntrumentationBufferAddress != 0;
        return maxineIntrumentationBufferAddress;
    }

    // TODO: Add comments, and example.
    public final int instrumentBranch(ConditionFlag taken, ConditionFlag notTaken, int disp) {
        int notTakenDisp = 0;
        asm.emitInt(0xeaffffff);
        asm.push(ConditionFlag.Always, 1 | 2 | 4 | 8 | 16 | 32 | 64 | 128 | 256 | 512 | 1024 | 2048 | 4096 | 16384);
        asm.mrsReadAPSR(ARMV7Assembler.ConditionFlag.Always, ARMV7.r4);
        asm.push(ConditionFlag.Always, 1 << 4);

        disp = disp - 16;
        asm.mov32BitConstant(taken, ARMV7.r1, disp);
        asm.addRegisters(taken, false, ARMV7.r1, ARMV7.r15, ARMV7.r1, 0, 0);
        if (notTaken != ConditionFlag.NeverUse) {
            notTakenDisp = 8 * 2 + 4 * 2 + 3 * 4;
            asm.mov32BitConstant(notTaken, ARMV7.r1, notTakenDisp);
            asm.addRegisters(notTaken, false, ARMV7.r1, ARMV7.r15, ARMV7.r1, 0, 0);
            notTakenDisp = 12;
        }
        asm.mov32BitConstant(ConditionFlag.Always, ARMV7.r0, -1);
        asm.mov32BitConstant(ConditionFlag.Always, ARMV7.r12, getInstrumentationBufferAddress());
        int instruction = ARMV7Assembler.blxHelper(ConditionFlag.Always, ARMV7.r12);
        asm.emitInt(instruction);
        asm.pop(ConditionFlag.Always, 1 << 4);
        asm.msrWriteAPSR(ARMV7Assembler.ConditionFlag.Always, ARMV7.r4);
        asm.pop(ConditionFlag.Always, 1 | 2 | 4 | 8 | 16 | 32 | 64 | 128 | 256 | 512 | 1024 | 2048 | 4096 | 16384);
        disp = disp - 3 * 8 - 3 * 4 - notTakenDisp - 2 * 4;
        return disp;
    }

    // TODO: Add comments, and example.
    public final int instrumentPC(ConditionFlag taken, ConditionFlag notTaken, boolean isAbsoluteAddress, CiRegister target, int pcAdjustment, boolean isMethodEntry) {
        assert isAbsoluteAddress == true : "instrumentNEWAbsolutePC only works for absolute addresses";
        assert !isMethodEntry || (isMethodEntry && (pcAdjustment == -4 || pcAdjustment == -16)) : "instrumentNEWAbsolutePC point is after the push $LR and decrement SP";

        if (isMethodEntry) {
            pcAdjustment -= 16;
        }

        asm.emitInt(0xeaffffff);
        asm.push(ConditionFlag.Always, 1 | 2 | 4 | 8 | 16 | 32 | 64 | 128 | 256 | 512 | 1024 | 2048 | 4096 | 16384);
        asm.mrsReadAPSR(ARMV7Assembler.ConditionFlag.Always, ARMV7.r4);
        asm.push(ConditionFlag.Always, 1 << 4);

        if (isMethodEntry) {
            asm.mov32BitConstant(ConditionFlag.Always, ARMV7.r0, -2);
            asm.mov32BitConstant(ConditionFlag.Always, ARMV7.r1, pcAdjustment - 20); // +20
        } else {
            asm.mov32BitConstant(ConditionFlag.Always, ARMV7.r0, -3);
            asm.mov32BitConstant(taken, ARMV7.r1, pcAdjustment);
            if (taken != ConditionFlag.Always) {
                asm.mov32BitConstant(notTaken, ARMV7.r1, +32);
                asm.addRegisters(notTaken, false, ARMV7.r1, ARMV7.r15, ARMV7.r1, 0, 0);
            }
        }
        asm.addRegisters(taken, false, ARMV7.r1, target, ARMV7.r1, 0, 0);
        asm.mov32BitConstant(ConditionFlag.Always, ARMV7.r8, getInstrumentationBufferAddress());
        int instruction = ARMV7Assembler.blxHelper(ConditionFlag.Always, ARMV7.r8);
        asm.emitInt(instruction);
        asm.pop(ConditionFlag.Always, 1 << 4);
        asm.msrWriteAPSR(ARMV7Assembler.ConditionFlag.Always, ARMV7.r4);
        asm.pop(ConditionFlag.Always, 1 | 2 | 4 | 8 | 16 | 32 | 64 | 128 | 256 | 512 | 1024 | 2048 | 4096 | 16384);
        return 40;
    }

    // TODO: Add comments, and example.
    public void instrument(boolean read, boolean data, boolean add, final CiRegister base, int immediate) {
        CiRegister valAddress = null;
        switch (base.getEncoding()) {
            case 0:
                valAddress = ARMV7.r12;
                break;
            case 8:
                valAddress = ARMV7.r12;
                break;
            case 9:
                valAddress = ARMV7.r12;
                break;
            case 1:
                valAddress = ARMV7.r12;
                break;
            case 2:
                valAddress = ARMV7.r12;
                break;
            case 3:
            case 4:
            case 5:
            case 6:
            case 7:
            case 10:
            case 11:
            case 14:
                valAddress = ARMV7.r12;
                break;
            case 12:
            case 13:
                valAddress = ARMV7.r1;
                break;
            default:
                assert false : "Instrumentation uses illegal base register!";
                break;
        }

        int orint = 0;
        if (!read) {
            orint |= 1;
        }
        if (!data) {
            orint |= 2;
        }

        asm.emitInt(0xeaffffff);
        asm.push(ConditionFlag.Always, 1 | 2 | 4 | 8 | 16 | 32 | 64 | 128 | 256 | 512 | 1024 | 2048 | 4096 | 16384);
        asm.mrsReadAPSR(ARMV7Assembler.ConditionFlag.Always, valAddress);
        asm.push(ConditionFlag.Always, 1 << valAddress.getEncoding());
        asm.mov32BitConstant(ConditionFlag.Always, valAddress, immediate);
        asm.addRegisters(ConditionFlag.Always, false, valAddress, valAddress, base, 0, 0);
        asm.or(ConditionFlag.Always, false, valAddress, valAddress, orint);
        asm.mov(ConditionFlag.Always, false, ARMV7.r0, valAddress);
        asm.mov32BitConstant(ConditionFlag.Always, ARMV7.r12, getInstrumentationBufferAddress());
        asm.blx(ARMV7.r12);
        asm.pop(ConditionFlag.Always, 1 << valAddress.getEncoding());
        asm.msrWriteAPSR(ARMV7Assembler.ConditionFlag.Always, valAddress);
        asm.pop(ConditionFlag.Always, 1 | 2 | 4 | 8 | 16 | 32 | 64 | 128 | 256 | 512 | 1024 | 2048 | 4096 | 16384);
    }

    // TODO: Add comments, and example.
    public void instrumentBX(ConditionFlag cond, CiRegister target) {
        int notTakenDisp = 0;
        ConditionFlag notTaken = cond.inverse();
        asm.push(ConditionFlag.Always, 1 | 2 | 4 | 8 | 16 | 32 | 64 | 128 | 256 | 512 | 1024 | 2048 | 4096 | 16384);
        asm.mov(cond, false, ARMV7.r1, target);
        if (notTaken != ConditionFlag.NeverUse) {
            notTakenDisp = 20;
            asm.mov32BitConstant(notTaken, ARMV7.r1, notTakenDisp);
            asm.addRegisters(notTaken, false, ARMV7.r1, ARMV7.r15, ARMV7.r1, 0, 0);
            notTakenDisp = 12;
        }
        asm.mov32BitConstant(ConditionFlag.Always, ARMV7.r0, -1);
        asm.mov32BitConstant(ConditionFlag.Always, ARMV7.r12, getInstrumentationBufferAddress());
        int instruction = ARMV7Assembler.blxHelper(ConditionFlag.Always, ARMV7.r12);
        asm.emitInt(instruction);
        asm.pop(ConditionFlag.Always, 1 | 2 | 4 | 8 | 16 | 32 | 64 | 128 | 256 | 512 | 1024 | 2048 | 4096 | 16384); // +4
    }
}
