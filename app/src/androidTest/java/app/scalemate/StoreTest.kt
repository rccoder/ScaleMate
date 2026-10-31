package app.scalemate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.core.ScaleCatalog
import app.scalemate.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import android.content.res.Configuration
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class StoreTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val database="test-scalemate.db"
    private lateinit var store: Store
    @Before fun setUp(){context.deleteDatabase(database);store=Store(context,database)}
    @After fun tearDown(){store.close();context.deleteDatabase(database)}
    @Test fun recordsSurviveReopenAndCommitIsIdempotent(){val m=Measurement("session","scale","Home",71.3,100000,28800);store.save(m);store.save(m);store.close();store=Store(context,database);assertEquals(1,store.records().size);assertEquals(m,store.records().first())}
    @Test fun bindingDuplicateAndRebindingPreserveIdentity(){val s=BoundScale("id","Home","A",false,ScaleCatalog.okokV20.id);store.bind(s);store.bind(s.copy(id="other"));assertEquals(1,store.scales().size);store.verify("id");store.rebind("id","B",ScaleCatalog.okokV11.id);assertEquals("id",store.scales().first().id);assertEquals("B",store.scales().first().address);assertEquals(ScaleCatalog.okokV11.id,store.scales().first().modelId);assertFalse(store.scales().first().verified)}
    @Test fun multipleDistinctScalesCoexistAndCanBeRemovedIndependently(){val first=BoundScale("first","Bathroom","A",true,ScaleCatalog.okokVf0.id);val second=BoundScale("second","Bedroom","B",true,ScaleCatalog.xiaomiWeightScale2.id);store.bind(first);store.bind(second);assertEquals(listOf(first,second),store.scales());store.unbind(first.id);assertEquals(listOf(second),store.scales())}
    @Test fun unbindingPreservesRecordSource(){store.bind(BoundScale("id","Home","A",false));store.save(Measurement("m","id","Home",71.3,100000,0));store.unbind("id");assertTrue(store.scales().isEmpty());assertEquals("Home",store.records().first().source)}
    @Test fun syncAndDeleteTombstoneAreDurable(){store.save(Measurement("m","id","Home",71.3,100000,0));store.status("m","synced","platform",true);store.status("m","delete_failed");val m=store.records().first();assertEquals("platform",m.platformId);assertEquals(1,m.attempts);assertEquals("delete_failed",m.status);store.deleteLocal("m");assertTrue(store.records().isEmpty())}
    @Test fun chineseAndEnglishNames(){fun name(tag:String):String{val config=Configuration(context.resources.configuration);config.setLocale(Locale.forLanguageTag(tag));return context.createConfigurationContext(config).getString(R.string.app_name)};assertEquals("称伴",name("zh"));assertEquals("ScaleMate",name("en"))}
}
