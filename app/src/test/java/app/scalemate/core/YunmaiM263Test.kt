package app.scalemate.core

import org.junit.Assert.*
import org.junit.Test

class YunmaiM263Test {
    private val address="ED:68:00:69:8E:CA"
    private fun packet()=byteArrayOf(0x69,0,0x68,0xed.toByte(),8,0x52,0x16,0x1d,1,0x1b,0xda.toByte(),0,0,0xdd.toByte())
    private fun checksum(p: ByteArray): ByteArray {p[13]=0;for(i in 7..12)p[13]=(p[13].toInt() xor p[i].toInt()).toByte();return p}
    @Test fun capturedWeight(){val frame=YunmaiM263.parse(address,0x8eca,packet())!!;assertEquals(71.3,frame.kg,0.00001);assertEquals(1,frame.state);assertEquals(29,frame.token)}
    @Test fun differentDeviceSameProtocol(){val p=packet();p[0]=0x22;p[1]=0x33;p[2]=0x44;p[3]=0x55;val f=YunmaiM263.parse("55:44:33:22:11:00",0x1100,p);assertNotNull(f);assertEquals("55:44:33:22:11:00",f!!.identity)}
    @Test fun macMustMatch(){assertNull(YunmaiM263.parse("00:00:00:00:00:00",0x8eca,packet()))}
    @Test fun rejectsBadChecksum(){val p=packet();p[13]=0;assertNull(YunmaiM263.parse(address,0x8eca,p))}
    @Test fun rejectsOtherModel(){val p=packet();p[4]=0x0b;assertNull(YunmaiM263.parse(address,0x8eca,checksum(p)))}
    @Test fun rejectsUnknownState(){val p=packet();p[8]=4;assertNull(YunmaiM263.parse(address,0x8eca,checksum(p)))}
    @Test fun rejectsOutOfRange(){val p=packet();p[9]=0xff.toByte();p[10]=0xff.toByte();assertNull(YunmaiM263.parse(address,0x8eca,checksum(p)))}
    @Test fun acceptsZeroReset(){val p=packet();p[9]=0;p[10]=0;assertEquals(0.0,YunmaiM263.parse(address,0x8eca,checksum(p))!!.kg,0.0)}
    @Test fun rejectsMalformedInput(){for(a in listOf("","xx:11:22:33:44:55","100:11:22:33:44:55"))assertNull(YunmaiM263.parse(a,0x8eca,packet()));assertNull(YunmaiM263.parse(address,0x8eca,null));assertNull(YunmaiM263.parse(address,0x8eca,ByteArray(13)))}
}
