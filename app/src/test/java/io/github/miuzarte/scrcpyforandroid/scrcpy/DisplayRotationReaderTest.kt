package io.github.miuzarte.scrcpyforandroid.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayRotationReaderTest {

    // Real capture (vivo V2436A, Android 16, `dumpsys window displays`, trimmed):
    // several display blocks precede the target; display 0 was in landscape (ROTATION_90).
    private val vivoSample = """
          Display: mDisplayId=678
          overrideConfig={0.0 ?mcc0mnc ?localeList ?layoutDir winConfig={ mBounds=Rect(0, 0 - 1024, 908) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen mRotation=ROTATION_0 isEmbedding:false} ?spacesystem eaMode=0}
            mRotation=0 mDeferredRotationPauseCount=0
          Display: mDisplayId=2 (organized)
          overrideConfig={0.0 ?mcc0mnc ?localeList ?layoutDir winConfig={ mBounds=Rect(0, 0 - 1172, 2748) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen mRotation=ROTATION_0 isEmbedding:false} ?spacesystem eaMode=0}
            mRotation=0 mDeferredRotationPauseCount=0
          Display: mDisplayId=0 (organized)
          overrideConfig={1.12 460mcc1mnc [zh_CN] ldltr land night finger winConfig={ mBounds=Rect(0, 0 - 2480, 2200) mDisplayRotation=ROTATION_90 mWindowingMode=fullscreen mRotation=ROTATION_90 isEmbedding:false} spacesystem eaMode=1}
          mHasSetIgnoreOrientationRequest=true ignoreOrientationRequest=true
          DisplayRotation
            mRotation=1 mDeferredRotationPauseCount=0
            mLandscapeRotation=ROTATION_90 mSeascapeRotation=ROTATION_270
            mUserRotationMode=USER_ROTATION_LOCKED mUserRotation=ROTATION_90 mCameraRotationMode=0 mAllowAllRotations=false
            mFixedToUserRotation=true
    """.trimIndent()

    @Test
    fun parsesDisplayZeroBlockNotOthers() {
        assertEquals(1, DisplayRotationReader.parseRotation(vivoSample, displayId = 0))
        assertEquals(0, DisplayRotationReader.parseRotation(vivoSample, displayId = 2))
        assertEquals(0, DisplayRotationReader.parseRotation(vivoSample, displayId = 678))
    }

    @Test
    fun ignoresRotationSpellingsInsideOverrideConfig() {
        // mRotation=ROTATION_90 / mRotation=undefined must not satisfy the digit field,
        // even when the standalone line is absent.
        val onlyOverride = """
              Display: mDisplayId=0 (organized)
              overrideConfig={winConfig={ mDisplayRotation=ROTATION_90 mRotation=ROTATION_90 isEmbedding:false}}
              overrideConfig={winConfig={ mDisplayRotation=undefined mRotation=undefined isEmbedding:false}}
        """.trimIndent()
        assertNull(DisplayRotationReader.parseRotation(onlyOverride, displayId = 0))
    }

    @Test
    fun portraitAndLandscapeBothParse() {
        val portrait = vivoSample.replace("mRotation=1 mDeferred", "mRotation=0 mDeferred")
        assertEquals(0, DisplayRotationReader.parseRotation(portrait, displayId = 0))
    }

    @Test
    fun missingOrJunkIsNull() {
        assertNull(DisplayRotationReader.parseRotation(null))
        assertNull(DisplayRotationReader.parseRotation(""))
        assertNull(DisplayRotationReader.parseRotation("no displays here"))
        // target display absent entirely
        assertNull(DisplayRotationReader.parseRotation(vivoSample, displayId = 999))
    }

    @Test
    fun displayIdPrefixMatchDoesNotLeak() {
        // "mDisplayId=02" must not be treated as display 0.
        val tricky = """
              Display: mDisplayId=02 (organized)
                mRotation=3 mDeferredRotationPauseCount=0
        """.trimIndent()
        assertNull(DisplayRotationReader.parseRotation(tricky, displayId = 0))
    }
}
