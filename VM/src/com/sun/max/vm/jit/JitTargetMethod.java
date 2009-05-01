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
package com.sun.max.vm.jit;

import java.util.*;

import com.sun.max.annotate.*;
import com.sun.max.collect.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.bytecode.*;
import com.sun.max.vm.bytecode.refmaps.*;
import com.sun.max.vm.collect.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.stack.*;

/**
 * A target method generated by the JIT.
 *
 * @author Laurent Daynes
 * @author Bernd Mathiske
 * @author Doug Simon
 */
public abstract class JitTargetMethod extends TargetMethod {

    protected JitTargetMethod(ClassMethodActor classMethodActor) {
        super(classMethodActor);
    }

    @Override
    public DynamicCompilerScheme compilerScheme() {
        return MaxineVM.hostOrTarget().configuration().jitScheme();
    }

    private int _optimizedCallerAdapterFrameCodeSize;

    /**
     * The size of the adapter frame code found at the {@linkplain CallEntryPoint#OPTIMIZED_ENTRY_POINT entry point} for
     * a call from a method compiled with the optimizing compiler.
     */
    public int optimizedCallerAdapterFrameCodeSize() {
        return _optimizedCallerAdapterFrameCodeSize;
    }

    private int _adapterReturnPosition;

    /**
     * @return the code position to which the JIT method returns in its optimized-to-JIT adapter code or -1 if there is no adapter.
     */
    public int adapterReturnPosition() {
        return _adapterReturnPosition;
    }

    /**
     * A bit map denoting which {@linkplain #directCallees() direct calls} in this target method correspond to calls
     * into the runtime derived from the constituent templates. These calls are
     * {@linkplain #linkDirectCalls(DynamicCompilerScheme) linked} using the entry point associated with the compiler
     * used to compile the runtime (i.e the opto compiler). All other direct calls are linked using the call entry point
     * associated with the JIT compiler.
     */
    private byte[] _isDirectCallToRuntime;

    public boolean isDirectCallToRuntime(int stopIndex) {
        return _isDirectCallToRuntime != null && (stopIndex < numberOfDirectCalls()) && ByteArrayBitMap.isSet(_isDirectCallToRuntime, 0, _isDirectCallToRuntime.length, stopIndex);
    }

    @Override
    protected CallEntryPoint callEntryPointForDirectCall(int directCallIndex) {
        if (_isDirectCallToRuntime != null && ByteArrayBitMap.isSet(_isDirectCallToRuntime, 0, _isDirectCallToRuntime.length, directCallIndex)) {
            return CallEntryPoint.OPTIMIZED_ENTRY_POINT;
        }
        return super.callEntryPointForDirectCall(directCallIndex);
    }

    @PROTOTYPE_ONLY
    @Override
    protected boolean isDirectCalleeInPrologue(int directCalleeIndex) {
        return stopPosition(directCalleeIndex) < targetCodePositionFor(0);
    }

    /**
     * An {@code int} array that encodes a mapping from bytecode positions to target code positions. A non-zero value
     * {@code val} at index {@code i} in the array encodes that there is a bytecode instruction whose opcode is at index
     * {@code i} in the bytecode array and whose target code position is {@code val}. Unless {@code i} is equal to the
     * length of the bytecode array in which case {@code val} denotes the target code position one byte past the
     * last target code byte emitted for the last bytecode instruction.
     */
    @INSPECTED
    private int[] _bytecodeToTargetCodePositionMap;

    public int targetCodePositionFor(int bytecodePosition) {
        return _bytecodeToTargetCodePositionMap[bytecodePosition];
    }

    @Override
    public Iterator<? extends BytecodeLocation> getBytecodeLocationsFor(Pointer instructionPointer) {
        final BytecodeLocation bytecodeLocation = new BytecodeLocation(classMethodActor(), bytecodePositionFor(instructionPointer.asPointer()));
        return Iterators.iterator(new BytecodeLocation[] {bytecodeLocation});
    }

    @Override
    public BytecodeLocation getBytecodeLocationFor(Pointer instructionPointer) {
        return new BytecodeLocation(classMethodActor(), bytecodePositionFor(instructionPointer.asPointer()));
    }

    /**
     * Gets the bytecode position for a machine code instruction address.
     *
     * @param instructionPointer
     *                an instruction pointer that may denote an instruction in this target method
     * @return the start position of the bytecode instruction that is implemented at the instruction pointer or -1 if
     *         {@code instructionPointer} denotes an instruction that does not correlate to any bytecode. This will be
     *         the case when {@code instructionPointer} is not in this target method or is in the adapter frame stub
     *         code, prologue or epilogue.
     */
    public int bytecodePositionFor(Pointer instructionPointer) {
        assert _bytecodeToTargetCodePositionMap != null;
        assert _bytecodeToTargetCodePositionMap.length > 0;
        final int targetCodePosition = targetCodePositionFor(instructionPointer);
        return bytecodePositionFor(targetCodePosition);
    }

    /**
     * Gets the bytecode position for a target code position in this JIT target method.
     *
     * @param targetCodePosition
     *                a target code position that may denote an instruction in this target method that correlates with a
     *                bytecode
     * @return the start position of the bytecode instruction that is implemented at {@code targetCodePosition} or -1 if
     *         {@code targetCodePosition} is outside the range(s) of target code positions in this target method that
     *         correlate with a bytecode.
     */
    public int bytecodePositionFor(int targetCodePosition) {
        assert _bytecodeToTargetCodePositionMap != null;
        assert _bytecodeToTargetCodePositionMap.length > 0;
        int bytecodePosition;
        if (targetCodePosition >= targetCodePositionFor(0)) {
            bytecodePosition = -1;
            for (int i = 0; i != _bytecodeToTargetCodePositionMap.length; i++) {
                if (targetCodePositionFor(i) > targetCodePosition) {
                    // For now just ensure we are to the left from a bytecode that is too far to the right:
                    bytecodePosition = i - 1;
                    break;
                }
            }
            assert bytecodePosition >= 0;

            // We are just left of the leftmost bytecode that is too far right.
            // Find the start of the bytecode instruction we are in:
            while (bytecodePosition >= 0 && _bytecodeToTargetCodePositionMap[bytecodePosition] == 0) {
                bytecodePosition--;
            }
            return bytecodePosition;
        }
        // The instruction pointer denotes a position in the adapter frame code or the prologue
        return -1;
    }

    /**
     * Correlates a bytecode range with a target code range. The target code range is typically the template code
     * produced by the JIT compiler for a single JVM instruction encoded in the bytecode range.
     *
     * @author Doug Simon
     */
    public static class CodeTranslation {

        private final int _bytecodePosition;
        private final int _bytecodeLength;
        private final int _targetCodePosition;
        private final int _targetCodeLength;

        /**
         * Creates an object that correlates a bytecode range with a target code range.
         *
         * @param bytecodePosition the first position in the bytecode range. This value is invalid if
         *            {@code bytecodeLength == 0}.
         * @param bytecodeLength the length of the bytecode range
         * @param targetCodePosition the first position in the target code range. This value is invalid if
         *            {@code targetCodeLength == 0}.
         * @param targetCodeLength the length of the target code range
         */
        public CodeTranslation(int bytecodePosition, int bytecodeLength, int targetCodePosition, int targetCodeLength) {
            _bytecodeLength = bytecodeLength;
            _bytecodePosition = bytecodePosition;
            _targetCodeLength = targetCodeLength;
            _targetCodePosition = targetCodePosition;
        }

        /**
         * Gets the first position in the bytecode range represented by this object. This value is only valid if
         * {@link #bytecodeLength()} does not return 0.
         */
        public int bytecodePosition() {
            return _bytecodePosition;
        }

        /**
         * Gets the position one past the last position in the bytecode range represented by this object. This value is
         * only valid if {@link #bytecodeLength()} does not return 0.
         */
        public int bytecodeEndPosition() {
            return _bytecodePosition + _bytecodeLength;
        }

        /**
         * Gets the length of the bytecode range represented by this object.
         */
        public int bytecodeLength() {
            return _bytecodeLength;
        }

        /**
         * Gets the first position in the target code range represented by this object. This value is only valid if
         * {@link #targetCodeLength()} does not return 0.
         */
        public int targetCodePosition() {
            return _targetCodePosition;
        }

        /**
         * Gets the position one past the last position in the target code range represented by this object. This value is only valid if
         * {@link #targetCodeLength()} does not return 0.
         */
        public int targetCodeEndPosition() {
            return _targetCodePosition + _targetCodeLength;
        }

        /**
         * Gets the length of the target code range represented by this object.
         */
        public int targetCodeLength() {
            return _targetCodeLength;
        }

        /**
         * Gets an object encapsulating the sub-range of a given bytecode array represented by this code translation.
         *
         * @param bytecode
         * @return null if {@code bytecodeLength() == 0}
         */
        public BytecodeBlock toBytecodeBlock(byte[] bytecode) {
            if (bytecodeLength() == 0) {
                return null;
            }
            return new BytecodeBlock(bytecode, bytecodePosition(), bytecodeEndPosition() - 1);
        }

        @Override
        public String toString() {
            final String bytecode = _bytecodeLength == 0 ? "[]" : "[" + _bytecodePosition + " - " + (bytecodeEndPosition() - 1) + "]";
            final String targetCode = _targetCodeLength == 0 ? "[]" : "[" + _targetCodePosition + " - " + (targetCodeEndPosition() - 1) + "]";
            return bytecode + " -> " + targetCode;
        }
    }

    /**
     * Gets a sequence of objects correlating bytecode ranges with the ranges of target code in this target method. The
     * returned sequence objects are exclusive of each other in terms of their target code ranges and they cover
     * every target code position in this target method.
     */
    public Sequence<CodeTranslation> codeTranslations() {
        final AppendableSequence<CodeTranslation> translations = new ArrayListSequence<CodeTranslation>();
        int startBytecodePosition = 0;
        int startTargetCodePosition = _bytecodeToTargetCodePositionMap[0];
        assert startTargetCodePosition != 0;
        translations.append(new CodeTranslation(0, 0, 0, startTargetCodePosition));
        for (int bytecodePosition = 1; bytecodePosition != _bytecodeToTargetCodePositionMap.length; ++bytecodePosition) {
            final int targetCodePosition = _bytecodeToTargetCodePositionMap[bytecodePosition];
            if (targetCodePosition != 0) {
                final CodeTranslation codeTranslation = new CodeTranslation(startBytecodePosition, bytecodePosition - startBytecodePosition, startTargetCodePosition, targetCodePosition - startTargetCodePosition);
                translations.append(codeTranslation);
                startTargetCodePosition = targetCodePosition;
                startBytecodePosition = bytecodePosition;
            }
        }
        if (startTargetCodePosition < code().length) {
            translations.append(new CodeTranslation(0, 0, startTargetCodePosition, code().length - startTargetCodePosition));
        }
        return translations;
    }

    @INSPECTED
    private BytecodeInfo[] _bytecodeInfos;

    private int _frameReferenceMapOffset;

    /**
     * @return references to the emitted templates or to byte codes in corresponding order to the above
     */
    public final BytecodeInfo[] bytecodeInfos() {
        return _bytecodeInfos;
    }

    public final void setGenerated(TargetBundle targetBundle,
                    int[] catchRangePositions,
                    int[] catchBlockPositions,
                    int[] stopPositions,
                    BytecodeStopsIterator bytecodeStopsIterator,
                    byte[] compressedJavaFrameDescriptors,
                    ClassMethodActor[] directCallees,
                    int numberOfIndirectCalls,
                    int numberOfSafepoints,
                    byte[] referenceMaps,
                    byte[] scalarLiteralBytes,
                    Object[] referenceLiterals,
                    Object codeOrCodeBuffer,
                    int optimizedCallerAdapterFrameCodeSize,
                    int adapterReturnPosition,
                    byte[] encodedInlineDataDescriptors,
                    ByteArrayBitMap isDirectRuntimeCall,
                    int[] bytecodeToTargetCodePositionMap,
                    BytecodeInfo[] bytecodeInfos,
                    int numberOfBlocks,
                    boolean[] blockStarts,
                    JitStackFrameLayout jitStackFrameLayout, TargetABI abi) {
        setGenerated(
            targetBundle,
            catchRangePositions,
            catchBlockPositions,
            stopPositions,
            compressedJavaFrameDescriptors,
            directCallees,
            numberOfIndirectCalls,
            numberOfSafepoints,
            0,
            referenceMaps,
            scalarLiteralBytes,
            referenceLiterals,
            codeOrCodeBuffer,
            encodedInlineDataDescriptors,
            jitStackFrameLayout.frameSize(),
            jitStackFrameLayout.frameReferenceMapSize(),
            abi,
            -1);
        _isDirectCallToRuntime = isDirectRuntimeCall == null ? null : isDirectRuntimeCall.bytes();
        _bytecodeToTargetCodePositionMap = bytecodeToTargetCodePositionMap;
        _bytecodeInfos = bytecodeInfos;
        _frameReferenceMapOffset = jitStackFrameLayout.frameReferenceMapOffset();
        _optimizedCallerAdapterFrameCodeSize = optimizedCallerAdapterFrameCodeSize;
        _adapterReturnPosition = adapterReturnPosition;
        if (stopPositions != null) {
            _referenceMapEditor = new JitReferenceMapEditor(this, numberOfBlocks, blockStarts, bytecodeStopsIterator, jitStackFrameLayout);
            final ReferenceMapInterpreter interpreter = ReferenceMapInterpreter.from(_referenceMapEditor.blockFrames());
            if (interpreter.performsAllocation() || MaxineVM.isPrototyping()) {
                // if computing the reference map requires allocation or if prototyping,
                // compute the reference map now
                finalizeReferenceMaps();
            }
        }
    }

    @Override
    public void finalizeReferenceMaps() {
        if (_referenceMapEditor != null) {
            if (Heap.traceGCRootScanning()) {
                final boolean lockDisabledSafepoints = Log.lock();
                Log.print("Finalizing JIT reference maps for ");
                Log.printMethodActor(classMethodActor(), true);
                Log.unlock(lockDisabledSafepoints);
            }
            _referenceMapEditor.fillInMaps(_bytecodeToTargetCodePositionMap);
            _referenceMapEditor = null;
            if (Heap.traceGCRootScanning()) {
                final boolean lockDisabledSafepoints = Log.lock();
                Log.print("Finalized JIT reference maps for ");
                Log.printMethodActor(classMethodActor(), true);
                Log.unlock(lockDisabledSafepoints);
            }
        }
    }

    @Override
    public boolean areReferenceMapsFinalized() {
        return _referenceMapEditor == null;
    }

    private JitReferenceMapEditor _referenceMapEditor;

    @Override
    public boolean prepareFrameReferenceMap(StackReferenceMapPreparer stackReferenceMapPreparer, Pointer instructionPointer, Pointer stackPointer, Pointer framePointer) {
        finalizeReferenceMaps();
        return stackReferenceMapPreparer.prepareFrameReferenceMap(this, instructionPointer, stackPointer, framePointer.plus(_frameReferenceMapOffset));
    }

    public Pointer getFramePointer(Pointer cpuStackPointer, Pointer cpuFramePointer, Pointer osSignalIntegerRegisters) {
        return cpuFramePointer;
    }
}
