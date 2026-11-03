package app.template.patches.messenger

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

/**
 * Messenger 575.0.0.0.1 — classes4.dex, LX/5zw;->BrT(Landroid/content/Intent;I)V
 * (cài đặt duy nhất của interface "launch intent for result" mà khay media dùng khi bấm nút Photos).
 *
 * Gốc:
 *   iget-object v1, p0, A00            // Fragment host của khay media
 *   ... lấy SecureContextHelper ...
 *   invoke-virtual {launcher, p1, v1, p2}, ->A0D(Intent, Fragment, I)Z
 *
 * Launcher của Facebook lọc intent qua các "IntentScope" (AnyApp / FamilyAccessibleByAnyApp ...).
 * Khi cấu hình từ server chuyển từ fail-open sang chặn thật, intent mở Photos bản Morphe bị
 * trả về null và bỏ qua âm thầm (không log, không crash) → icon vẫn hiện nhưng bấm không có gì.
 *
 * Patch: gọi thẳng Fragment.startActivityForResult(intent, requestCode) ngay sau khi lấy Fragment.
 * Kết quả trả về vẫn đi vào onActivityResult của đúng Fragment đó như luồng gốc.
 */
internal object MediaPickerLaunchForResultFingerprint : Fingerprint(
    isStatic = false,
    returnType = "V",
    parameters = listOf("Landroid/content/Intent;", "I"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this", location = MatchFirst()),
        methodCall(
            parameters = listOf("Landroid/content/Intent;", "Landroidx/fragment/app/Fragment;", "I"),
            returnType = "Z",
        ),
    ),
    // Wrapper rất ngắn (9 lệnh). Loại các hàm dài hơn có cùng chữ ký.
    custom = { method, _ -> (method.implementation?.instructions?.count() ?: 0) <= 12 },
)

@Suppress("unused")
val bypassMediaPickerIntentScopePatch = bytecodePatch(
    name = "Fix Photos picker button not opening",
    description = "Sửa lỗi bấm nút Photos trong khay ảnh không có phản ứng sau khi Messenger tải cấu hình từ server.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MESSENGER)

    execute {
        MediaPickerLaunchForResultFingerprint.let {
            val igetIndex = it.instructionMatches[0].index
            val fragmentRegister = it.method.getInstruction<TwoRegisterInstruction>(igetIndex).registerA

            it.method.addInstructions(
                igetIndex + 1,
                """
                    invoke-virtual {v$fragmentRegister, p1, p2}, Landroidx/fragment/app/Fragment;->startActivityForResult(Landroid/content/Intent;I)V
                    return-void
                """,
            )
        }
    }
}
