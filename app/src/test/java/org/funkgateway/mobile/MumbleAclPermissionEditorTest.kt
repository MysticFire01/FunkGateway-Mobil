package org.funkgateway.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MumbleAclPermissionEditorTest {

    private val editor = MumbleAclPermissionEditor

    @Test
    fun unsetPermissionIsRecognized() {
        assertEquals(
            MumbleAclPermissionEditor.State.UNSET,
            editor.state(0, 0, 8)
        )
    }

    @Test
    fun allowedPermissionIsRecognized() {
        assertEquals(
            MumbleAclPermissionEditor.State.ALLOW,
            editor.state(8, 0, 8)
        )
    }

    @Test
    fun deniedPermissionIsRecognized() {
        assertEquals(
            MumbleAclPermissionEditor.State.DENY,
            editor.state(0, 8, 8)
        )
    }

    @Test
    fun switchingFromAllowToDenyClearsAllow() {
        val result = editor.update(
            8, 0, 8, MumbleAclPermissionEditor.State.DENY
        )
        assertEquals(0, result.first)
        assertEquals(8, result.second)
    }

    @Test
    fun unknownPermissionBitsArePreserved() {
        val unknownBit = 1 shl 25
        val result = editor.update(
            unknownBit or 8,
            unknownBit,
            8,
            MumbleAclPermissionEditor.State.UNSET
        )
        assertEquals(unknownBit, result.first)
        assertEquals(unknownBit, result.second)
    }

    @Test
    fun invalidPermissionMaskIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            editor.update(
                0, 0, 3, MumbleAclPermissionEditor.State.ALLOW
            )
        }
    }
}
