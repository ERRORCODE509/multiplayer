package matlabmaster.multiplayer.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Redirects method calls in a class file to static methods of another class: every invokestatic of owner.name(desc)
 * listed in the redirects becomes an invokestatic of the same name and descriptor on another class, and every
 * invokevirtual of a listed owner.name(desc) becomes an invokestatic of the same name taking the receiver as its
 * first argument. Both instructions are 3 bytes and take the same stack, so only the constant pool grows (the new
 * Class, NameAndType and Methodref constants are appended) and each call is rewritten in place: no instruction
 * moves, no jump or stack map needs updating, and the result verifies like the original.
 */
public class ClassPatcher {
    /** Where one method's calls go: the new owner, and for an invokevirtual the static method's descriptor. */
    private static final class Target {
        final String newOwner;
        final String newDesc; //null: same descriptor (static to static)

        Target(String newOwner, String newDesc) {
            this.newOwner = newOwner;
            this.newDesc = newDesc;
        }
    }

    /** "owner.name(desc)" of the original call, e.g. "com/fs/starfarer/api/util/Misc.getDistanceLY(...)F". */
    private final Map<String, Target> redirects = new HashMap<>();
    private int patchedCalls;

    /** invokestatic owner.name(desc) becomes invokestatic newOwner.name(desc). */
    public void redirect(String owner, String name, String desc, String newOwner) {
        redirects.put(owner + "." + name + desc, new Target(newOwner, null));
    }

    /**
     * invokevirtual owner.name(desc) becomes invokestatic newOwner.name(newDesc), where newDesc takes the receiver
     * first. Its parameter types only have to accept what's on the stack (e.g. Object for an obfuscated class).
     */
    public void redirectVirtual(String owner, String name, String desc, String newOwner, String newDesc) {
        redirects.put(owner + "." + name + desc, new Target(newOwner, newDesc));
    }

    /** How many calls the last patch() rewrote. */
    public int patchedCalls() {
        return patchedCalls;
    }

    /** The patched class, or null if it has no call to redirect (then it's left as it was). */
    public byte[] patch(byte[] classFile) throws IOException {
        patchedCalls = 0;
        ByteBuffer in = ByteBuffer.wrap(classFile);
        if (in.getInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.getShort(); in.getShort(); //minor, major
        int count = in.getShort() & 0xFFFF;
        int poolStart = in.position();
        int[] offsets = new int[count];
        byte[] tags = new byte[count];
        for (int i = 1; i < count; i++) {
            offsets[i] = in.position();
            byte tag = in.get();
            tags[i] = tag;
            switch (tag) {
                case 1: { int len = in.getShort() & 0xFFFF; in.position(in.position() + len); break; } //Utf8
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: in.position(in.position() + 4); break;
                case 5: case 6: in.position(in.position() + 8); i++; break;                     //Long, Double: two slots
                case 7: case 8: case 16: case 19: case 20: in.position(in.position() + 2); break;
                case 15: in.position(in.position() + 3); break;                                 //MethodHandle
                default: throw new IOException("unknown constant pool tag " + tag);
            }
        }
        int poolEnd = in.position();

        //which Methodrefs to redirect: old index -> where to
        Map<Integer, Target> targets = new HashMap<>();
        for (int i = 1; i < count; i++) {
            if (tags[i] != 10) continue; //Methodref
            int classIdx = in.getShort(offsets[i] + 1) & 0xFFFF;
            int natIdx = in.getShort(offsets[i] + 3) & 0xFFFF;
            String owner = utf(in, offsets, in.getShort(offsets[classIdx] + 1) & 0xFFFF);
            String name = utf(in, offsets, in.getShort(offsets[natIdx] + 1) & 0xFFFF);
            String desc = utf(in, offsets, in.getShort(offsets[natIdx] + 3) & 0xFFFF);
            Target target = redirects.get(owner + "." + name + desc);
            if (target != null) targets.put(i, target);
        }
        if (targets.isEmpty()) return null;

        //append the new constants: Utf8 + Class per new owner, a Utf8 + NameAndType per new descriptor, then a
        //Methodref per redirected method
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(extra);
        int next = count;
        Map<String, Integer> ownerClass = new HashMap<>();
        Map<Integer, Integer> newIndex = new HashMap<>(); //static to static
        Map<Integer, Integer> newVirtualIndex = new HashMap<>(); //virtual to static
        for (Map.Entry<Integer, Target> t : targets.entrySet()) {
            Target target = t.getValue();
            Integer cls = ownerClass.get(target.newOwner);
            if (cls == null) {
                out.writeByte(1); out.writeUTF(target.newOwner); int utf = next++;
                out.writeByte(7); out.writeShort(utf); cls = next++;
                ownerClass.put(target.newOwner, cls);
            }
            int nat = in.getShort(offsets[t.getKey()] + 3) & 0xFFFF;
            if (target.newDesc != null) {
                int nameIdx = in.getShort(offsets[nat] + 1) & 0xFFFF;
                out.writeByte(1); out.writeUTF(target.newDesc); int descIdx = next++;
                out.writeByte(12); out.writeShort(nameIdx); out.writeShort(descIdx); nat = next++;
            }
            out.writeByte(10); out.writeShort(cls); out.writeShort(nat);
            (target.newDesc == null ? newIndex : newVirtualIndex).put(t.getKey(), next++);
        }
        if (next > 0xFFFF) throw new IOException("constant pool full");

        //the rest of the class, with each redirected invokestatic's operand rewritten
        byte[] rest = new byte[classFile.length - poolEnd];
        System.arraycopy(classFile, poolEnd, rest, 0, rest.length);
        ByteBuffer r = ByteBuffer.wrap(rest);
        r.position(6); //access, this, super
        int interfaces = r.getShort() & 0xFFFF;
        r.position(r.position() + 2 * interfaces);
        skipMembers(r); //fields
        int methods = r.getShort() & 0xFFFF;
        for (int m = 0; m < methods; m++) {
            r.position(r.position() + 6);
            int attrs = r.getShort() & 0xFFFF;
            for (int a = 0; a < attrs; a++) {
                String attrName = utf(in, offsets, r.getShort() & 0xFFFF);
                int len = r.getInt();
                int attrStart = r.position();
                if (attrName.equals("Code")) {
                    int codeStart = attrStart + 8; //max_stack, max_locals, code_length
                    int codeLen = r.getInt(attrStart + 4);
                    rewriteCode(r, codeStart, codeLen, newIndex, newVirtualIndex);
                }
                r.position(attrStart + len);
            }
        }

        ByteArrayOutputStream result = new ByteArrayOutputStream(classFile.length + extra.size());
        result.write(classFile, 0, poolStart - 2);
        result.write((next >> 8) & 0xFF); result.write(next & 0xFF);
        result.write(classFile, poolStart, poolEnd - poolStart);
        result.write(extra.toByteArray());
        result.write(rest);
        return result.toByteArray();
    }

    private void rewriteCode(ByteBuffer r, int start, int length, Map<Integer, Integer> newIndex,
                             Map<Integer, Integer> newVirtualIndex) throws IOException {
        int pc = 0;
        while (pc < length) {
            int op = r.get(start + pc) & 0xFF;
            if (op == 0xB8 || op == 0xB6) { //invokestatic, invokevirtual
                int idx = r.getShort(start + pc + 1) & 0xFFFF;
                Integer replacement = (op == 0xB8 ? newIndex : newVirtualIndex).get(idx);
                if (replacement != null) {
                    r.put(start + pc, (byte) 0xB8);
                    r.putShort(start + pc + 1, (short) (int) replacement);
                    patchedCalls++;
                }
            }
            pc += instructionLength(r, start, pc, op);
        }
    }

    /** The length of the instruction at pc (JVM spec, chapter 6). */
    static int instructionLength(ByteBuffer r, int start, int pc, int op) throws IOException {
        switch (op) {
            case 0x10: case 0x12: case 0x15: case 0x16: case 0x17: case 0x18: case 0x19: case 0x36: case 0x37:
            case 0x38: case 0x39: case 0x3A: case 0xA9: case 0xBC:
                return 2;
            case 0x11: case 0x13: case 0x14: case 0x84: case 0xA7: case 0xA8: case 0xB2: case 0xB3: case 0xB4:
            case 0xB5: case 0xB6: case 0xB7: case 0xB8: case 0xBB: case 0xBD: case 0xC0: case 0xC1: case 0xC6: case 0xC7:
                return 3;
            case 0xC5:
                return 4;
            case 0xB9: case 0xBA: case 0xC8: case 0xC9:
                return 5;
            case 0xC4: //wide
                return (r.get(start + pc + 1) & 0xFF) == 0x84 ? 6 : 4;
            case 0xAA: { //tableswitch
                int p = pc + 1 + ((4 - ((pc + 1) % 4)) % 4);
                int low = r.getInt(start + p + 4), high = r.getInt(start + p + 8);
                return p - pc + 12 + 4 * (high - low + 1);
            }
            case 0xAB: { //lookupswitch
                int p = pc + 1 + ((4 - ((pc + 1) % 4)) % 4);
                int pairs = r.getInt(start + p + 4);
                return p - pc + 8 + 8 * pairs;
            }
            default:
                if (op >= 0x99 && op <= 0xA6) return 3; //if*
                if (op > 0xC9) throw new IOException("unknown opcode " + op + " at " + pc);
                return 1;
        }
    }

    private static void skipMembers(ByteBuffer r) {
        int n = r.getShort() & 0xFFFF;
        for (int i = 0; i < n; i++) {
            r.position(r.position() + 6);
            int attrs = r.getShort() & 0xFFFF;
            for (int a = 0; a < attrs; a++) {
                r.getShort();
                int len = r.getInt(); //read first: position() must be taken after the length is consumed
                r.position(r.position() + len);
            }
        }
    }

    private static String utf(ByteBuffer in, int[] offsets, int index) {
        int off = offsets[index];
        int len = in.getShort(off + 1) & 0xFFFF;
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = in.get(off + 3 + i);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8); //modified UTF-8; fine for class/method names
    }
}
