package app.thebridge

import app.thebridge.data.phone.ArcCommand
import app.thebridge.data.phone.CallTarget
import app.thebridge.data.phone.PhoneContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArcPhoneTest {
    private fun name(s: String) = CallTarget.Name(s)

    @Test fun `call commands are understood however they are said`() {
        assertEquals(name("Sharon"), ArcCommand.parseCall("call Sharon"))
        assertEquals(name("sharon"), ArcCommand.parseCall("arc call sharon"))
        assertEquals(name("Sharon"), ArcCommand.parseCall("Hey Arc, call Sharon please."))
        assertEquals(name("my mum"), ArcCommand.parseCall("Ring my mum for me"))
        assertEquals(name("Dr Bello"), ArcCommand.parseCall("Can you phone Dr Bello now?"))
        assertEquals(name("Tunde"), ArcCommand.parseCall("Coachy call Tunde", coachName = "Coachy"))
        assertEquals(CallTarget.Number("08030000001"), ArcCommand.parseCall("dial 0803 000 0001"))
        assertEquals(CallTarget.Number("+2348030000001"), ArcCommand.parseCall("call +234 803 000 0001"))
    }

    @Test fun `anything else goes to the coach`() {
        assertNull(ArcCommand.parseCall("remind me to call Sharon at 5"))     // a reminder, with a time
        assertNull(ArcCommand.parseCall("call Sharon at 5pm"))
        assertNull(ArcCommand.parseCall("call Sharon tomorrow"))
        assertNull(ArcCommand.parseCall("call it a day"))
        assertNull(ArcCommand.parseCall("call me later"))
        assertNull(ArcCommand.parseCall("I need to call the bank about my loan before Friday"))
        assertNull(ArcCommand.parseCall("How do I stay focused?"))
        assertNull(ArcCommand.parseCall("Calling people makes me nervous"))
    }

    @Test fun `the best matching contacts win, and the same number saved twice counts once`() {
        val book = listOf(
            PhoneContact("Sharon Okafor", "+234 803 000 0001", "Mobile"),
            PhoneContact("Sharon O.", "0803 000 0001", "Mobile"),          // same number, saved again
            PhoneContact("Sharonda Lee", "0803 000 0002"),
            PhoneContact("Mum", "0803 000 0003"),
            PhoneContact("Dr. Bello", "0803 000 0004"),
            PhoneContact("Shola", "0803 000 0005"),
        )
        assertEquals(listOf("Sharon Okafor"), ArcCommand.rank("Sharon", book).map { it.name })
        assertEquals(listOf("Mum"), ArcCommand.rank("my mum", book).map { it.name })
        assertEquals(listOf("Dr. Bello"), ArcCommand.rank("Dr Bello", book).map { it.name })
        assertEquals(listOf("Dr. Bello"), ArcCommand.rank("bello", book).map { it.name })
        assertEquals(listOf("Sharon Okafor", "Sharonda Lee"), ArcCommand.rank("shar", book).map { it.name })
        assertEquals(emptyList<PhoneContact>(), ArcCommand.rank("Kemi", book))
        assertEquals("dr bello", ArcCommand.norm("  Dr. Bélló "))
    }
}
