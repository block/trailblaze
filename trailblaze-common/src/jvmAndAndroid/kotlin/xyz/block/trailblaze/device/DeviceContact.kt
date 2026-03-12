package xyz.block.trailblaze.device

/**
 * Contact payload used for inserting contacts on both Android and JVM/adb backends.
 *
 * The defaults match current benchmark usage, while allowing future extensions
 * without changing the executor signature again.
 */
data class DeviceContact(
  val displayName: String,
  val phoneNumber: String,
  /**
   * The sync account type for the raw contact, passed to
   * `ContactsContract.RawContacts.ACCOUNT_TYPE`. This identifies the sync adapter that owns the
   * contact (e.g. `"com.google"` for Google accounts). When `null`, the contact is created as a
   * device-local contact not associated with any sync account.
   */
  val accountType: String? = null,
  /**
   * The sync account name for the raw contact, passed to
   * `ContactsContract.RawContacts.ACCOUNT_NAME`. This is typically the user's account identifier
   * (e.g. `"user@gmail.com"` for a Google account). When `null`, the contact is created as a
   * device-local contact not associated with any sync account.
   */
  val accountName: String? = null,
  /**
   * Phone type constant passed to `ContactsContract.CommonDataKinds.Phone.TYPE`.
   * Defaults to `2` which equals `ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE`.
   * The Android constant can't be referenced directly because this class is in a
   * `jvmAndAndroid` source set without an Android SDK dependency.
   */
  val phoneType: Int = 2,
)
