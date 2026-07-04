package radio.ks3ckc.ft8af.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R

/**
 * Read-only list of the most recent decoded messages, newest first. Pushed from
 * [QsoStatusScreen]'s action strip; rows are not clickable.
 */
class RecentDecodesScreen(carContext: CarContext) : Screen(carContext) {

    private var attached = false

    /**
     * Observe the decode list once the engine singleton exists. Attached lazily
     * from [onGetTemplate] rather than in init, so a Screen that somehow renders
     * before the engine is up still hooks the list on a later render instead of
     * staying stale forever. Fires on every decode pass; the observer detaches
     * with the Screen's lifecycle.
     */
    private fun maybeAttach(vm: MainViewModel) {
        if (attached) return
        attached = true
        vm.mutableFt8MessageList.observe(this) { invalidate() }
    }

    override fun onGetTemplate(): Template {
        val vm = MainViewModel.peekInstance() ?: return openPhoneTemplate(carContext)
        maybeAttach(vm)
        // publishFt8MessageList() posts a defensive copy that is never mutated
        // after posting, so the value is safe to iterate directly.
        val messages = vm.mutableFt8MessageList.value.orEmpty()
        val rows = buildCarDecodeRows(
            decodes = messages.map {
                Triple(it.utcTime, it.getMessageText(), if (it.hasSnr()) it.snr else null)
            },
            maxRows = listRowLimit(carContext),
        )
        val itemList = ItemList.Builder().apply {
            if (rows.isEmpty()) {
                setNoItemsMessage(carContext.getString(R.string.car_no_decodes))
            }
            rows.forEach { row ->
                addItem(
                    Row.Builder()
                        .setTitle(row.text)
                        .addText(carDecodeSecondary(row.utcTimeMs, row.snrLabel))
                        .build(),
                )
            }
        }.build()
        return ListTemplate.Builder()
            .setSingleList(itemList)
            .setTitle(carContext.getString(R.string.car_decodes_title))
            .setHeaderAction(Action.BACK)
            .build()
    }
}

private const val DEFAULT_LIST_ROWS = 6

/** The host's list row limit; older hosts (car app API < 2) get a safe default. */
internal fun listRowLimit(carContext: CarContext): Int =
    if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    } else {
        DEFAULT_LIST_ROWS
    }
