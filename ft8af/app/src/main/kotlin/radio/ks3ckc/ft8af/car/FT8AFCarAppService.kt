package radio.ks3ckc.ft8af.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto entry point: a read-only projection of the running FT8 QSO
 * sequence (see [QsoStatusScreen]). Runs in the same process as the engine, so
 * the screens observe the MainViewModel singleton's LiveData directly — this
 * service never starts or controls the engine itself.
 */
class FT8AFCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds accept any host so the Desktop Head Unit works.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Despite the "sample" name, hosts_allowlist_sample is the
            // library-shipped list of official Google host signatures
            // (gearhead, Automotive OS) and the production pattern in the
            // Android for Cars docs; it updates with the library, unlike an
            // app-owned copy, which would go stale.
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = FT8AFCarSession()
}

class FT8AFCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = QsoStatusScreen(carContext)
}
