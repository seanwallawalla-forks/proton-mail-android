/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonMail.
 *
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.attachments

import android.content.Context
import android.os.Environment
import androidx.hilt.Assisted
import androidx.hilt.work.WorkerInject
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.protonmail.android.activities.messageDetails.repository.MessageDetailsRepository
import ch.protonmail.android.api.models.room.attachmentMetadata.AttachmentMetadata
import ch.protonmail.android.api.models.room.attachmentMetadata.AttachmentMetadataDatabase
import ch.protonmail.android.api.models.room.messages.Attachment
import ch.protonmail.android.core.Constants
import ch.protonmail.android.core.UserManager
import ch.protonmail.android.crypto.AddressCrypto
import ch.protonmail.android.crypto.Crypto
import ch.protonmail.android.events.DownloadEmbeddedImagesEvent
import ch.protonmail.android.events.DownloadedAttachmentEvent
import ch.protonmail.android.events.Status
import ch.protonmail.android.jobs.helper.EmbeddedImage
import ch.protonmail.android.storage.AttachmentClearingService
import ch.protonmail.android.utils.AppUtil
import me.proton.core.util.kotlin.EMPTY_STRING
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import javax.inject.Inject

// region constants
private const val ATTACHMENT_UNKNOWN_FILE_NAME = "attachment"
private const val KEY_INPUT_DATA_MESSAGE_ID_STRING = "KEY_INPUT_DATA_MESSAGE_ID_STRING"
private const val KEY_INPUT_DATA_USERNAME_STRING = "KEY_INPUT_DATA_USERNAME_STRING"
internal const val KEY_INPUT_DATA_ATTACHMENT_ID_STRING = "KEY_INPUT_DATA_ATTACHMENT_ID_STRING"
// endregion

/**
 * Represents one unit of work downloading embedded attachments for
 * [Message][ch.protonmail.android.api.models.room.messages.Message] and saving them to local app storage.
 *
 * InputData has to contain non-null values for:
 * - messageId
 *
 * @see androidx.work.WorkManager
 * @see androidx.work.Data
 */

class DownloadEmbeddedAttachmentsWorker @WorkerInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val userManager: UserManager,
    private val messageDetailsRepository: MessageDetailsRepository,
    private val attachmentMetadataDatabase: AttachmentMetadataDatabase,
    private val downloadHelper: AttachmentsHelper
) : Worker(context, params) {

    override fun doWork(): Result {

        // sanitize input
        val messageId = inputData.getString(KEY_INPUT_DATA_MESSAGE_ID_STRING)
            ?: return Result.failure()
        val username = inputData.getString(KEY_INPUT_DATA_USERNAME_STRING)
            ?: return Result.failure()

        val singleAttachmentId = inputData.getString(KEY_INPUT_DATA_ATTACHMENT_ID_STRING)

        var attachments: List<Attachment>
        var message = messageDetailsRepository.findSearchMessageById(messageId)
        if (message != null) { // use search or standard message database, if Message comes from search
            attachments = messageDetailsRepository.findSearchAttachmentsByMessageId(messageId)
        } else {
            message = messageDetailsRepository.findMessageByIdBlocking(messageId)
            attachments = messageDetailsRepository.findAttachmentsByMessageId(messageId)
        }

        if (message == null) return Result.failure()

        val addressCrypto = Crypto.forAddress(userManager, username, message.addressID!!)
        // We need this outside of this because the embedded attachments are set once the message is actually decrypted
        try {
            message.decrypt(addressCrypto)
        } catch (exception: GeneralSecurityException) {
            Timber.e(exception, "Decrypt exception")
        }
        if (message.isPGPMime) {
            attachments = message.Attachments
        }

        val embeddedImages = attachments.mapNotNull {
            downloadHelper.fromAttachmentToEmbededImage(it, message.embeddedImagesArray)
        }
        val otherAttachments = attachments.filter { attachment ->
            embeddedImages.find { attachment.attachmentId == it.attachmentId } == null
        }
        val singleAttachment = otherAttachments.find { it.attachmentId == singleAttachmentId }

        val pathname = applicationContext.filesDir.toString() + Constants.DIR_EMB_ATTACHMENT_DOWNLOADS + messageId
        Timber.v("Attachment path: $pathname")

        return if (singleAttachment != null) {
            val attachmentDirectoryFile = File("$pathname/$singleAttachmentId")
            if (!downloadHelper.createAttachmentFolderIfNeeded(attachmentDirectoryFile)) {
                return Result.failure()
            }
            handleSingleAttachment(singleAttachment, addressCrypto, attachmentDirectoryFile, messageId)
        } else {
            val attachmentsDirectoryFile = File(pathname)
            if (!downloadHelper.createAttachmentFolderIfNeeded(attachmentsDirectoryFile)) {
                return Result.failure()
            }
            handleEmbeddedImages(embeddedImages, addressCrypto, attachmentsDirectoryFile, messageId)
        }
    }

    private fun handleSingleAttachment(
        attachment: Attachment,
        crypto: AddressCrypto,
        attachmentsDirectoryFile: File,
        messageId: String
    ): Result {

        AppUtil.postEventOnUi(
            DownloadedAttachmentEvent(
                Status.STARTED, attachment.fileName, attachment.attachmentId, messageId, false
            )
        )

        val filenameInCache = attachment.fileName?.replace(" ", "_")?.replace("/", ":")
        val attachmentFile = File(attachmentsDirectoryFile, filenameInCache ?: EMPTY_STRING) // TODO: Check this
        Timber.v("handleSingleAttachment filename:$filenameInCache DirectoryFile:$attachmentsDirectoryFile")

        applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { externalDirectory ->
            val uniqueFilenameInDownloads = downloadHelper.createUniqueFilename(
                attachment.fileName ?: ATTACHMENT_UNKNOWN_FILE_NAME,
                externalDirectory
            )

            try {
                val decryptedByteArray = downloadHelper.getAttachmentData(
                    crypto,
                    attachment.mimeData,
                    attachment.attachmentId!!,
                    attachment.keyPackets,
                    attachment.fileSize,
                    uniqueFilenameInDownloads
                )
                FileOutputStream(attachmentFile).use {
                    it.write(decryptedByteArray)
                }

                val attachmentMetadata = AttachmentMetadata(
                    attachment.attachmentId!!,
                    attachment.fileName!!,
                    attachment.fileSize,
                    attachment.messageId + "/" + attachment.attachmentId + "/" + filenameInCache,
                    attachment.messageId, System.currentTimeMillis()
                )
                attachmentMetadataDatabase.insertAttachmentMetadata(attachmentMetadata)

                attachmentFile.copyTo(
                    File(
                        externalDirectory,
                        uniqueFilenameInDownloads
                    )
                )

            } catch (e: Exception) {
                Timber.e(e, "handleSingleAttachment exception")
                AppUtil.postEventOnUi(
                    DownloadedAttachmentEvent(Status.FAILED, filenameInCache, attachment.attachmentId, messageId, false)
                )
                return Result.failure()
            }
            AppUtil.postEventOnUi(
                DownloadedAttachmentEvent(
                    Status.SUCCESS, uniqueFilenameInDownloads, attachment.attachmentId, messageId, false
                )
            )
        } ?: run {
            Timber.w("Unable to access DIRECTORY_DOWNLOADS to save attachments")
        }

        AttachmentClearingService.startRegularClearUpService() // TODO don't call it every time we download attachments
        return Result.success()
    }

    private fun handleEmbeddedImages(
        embeddedImages: List<EmbeddedImage>,
        crypto: AddressCrypto,
        attachmentsDirectoryFile: File,
        messageId: String
    ): Result {

        Timber.v("handleEmbeddedImages images:$embeddedImages DirectoryFile:$attachmentsDirectoryFile")
        // short-circuit if all attachments are already downloaded
        if (downloadHelper.areAllAttachmentsAlreadyDownloaded(
                attachmentsDirectoryFile,
                messageId,
                embeddedImages,
                attachmentMetadataDatabase
            )
        ) {
            AppUtil.postEventOnUi(DownloadEmbeddedImagesEvent(Status.SUCCESS, embeddedImages))
            return Result.success()
        }

        AppUtil.postEventOnUi(DownloadEmbeddedImagesEvent(Status.STARTED))

        var failure = false
        embeddedImages.forEachIndexed { index, embeddedImage ->

            val filename = downloadHelper.calculateFilename(embeddedImage.fileNameFormatted!!, index)
            val attachmentFile = File(attachmentsDirectoryFile, filename)

            try {

                val decryptedByteArray = downloadHelper.getAttachmentData(
                    crypto,
                    embeddedImage.mimeData,
                    embeddedImage.attachmentId,
                    embeddedImage.key
                )
                FileOutputStream(attachmentFile).use {
                    it.write(decryptedByteArray)
                }

                val embeddedImageWithFile = embeddedImage.copy(localFileName = filename)
                val attachmentMetadata = AttachmentMetadata(
                    embeddedImageWithFile.attachmentId,
                    embeddedImageWithFile.fileNameFormatted!!, embeddedImageWithFile.size,
                    embeddedImageWithFile.messageId + "/" + filename,
                    embeddedImageWithFile.messageId, System.currentTimeMillis()
                )
                attachmentMetadataDatabase.insertAttachmentMetadata(attachmentMetadata)

            } catch (e: Exception) {
                Timber.e(e, "handleEmbeddedImages exception")
                failure = true
            }
        }

        return if (failure) {
            AppUtil.postEventOnUi(DownloadEmbeddedImagesEvent(Status.FAILED))
            Result.failure()
        } else {
            AttachmentClearingService.startRegularClearUpService() // TODO don't call it every time we download attachments
            AppUtil.postEventOnUi(DownloadEmbeddedImagesEvent(Status.SUCCESS, embeddedImages))
            Result.success()
        }
    }

    class Enqueuer @Inject constructor(private val workManager: WorkManager) {

        private val uniqueWorkIdPrefix = "downloadEmbeddedAttachmentsWork"

        fun enqueue(
            messageId: String,
            username: String,
            attachmentId: String
        ): Operation {

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val attachmentsWorkRequest =
                OneTimeWorkRequest.Builder(DownloadEmbeddedAttachmentsWorker::class.java)
                    .setConstraints(constraints)
                    .setInputData(
                        workDataOf(
                            KEY_INPUT_DATA_MESSAGE_ID_STRING to messageId,
                            KEY_INPUT_DATA_USERNAME_STRING to username,
                            KEY_INPUT_DATA_ATTACHMENT_ID_STRING to attachmentId
                        )
                    )
                    .build()

            return workManager.enqueueUniqueWork(
                "$uniqueWorkIdPrefix-$attachmentId",
                ExistingWorkPolicy.KEEP,
                attachmentsWorkRequest
            )
        }
    }
}
