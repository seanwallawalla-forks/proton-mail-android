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

import ch.protonmail.android.activities.messageDetails.repository.MessageDetailsRepository
import ch.protonmail.android.api.models.room.messages.Message
import ch.protonmail.android.crypto.AddressCrypto
import kotlinx.coroutines.withContext
import me.proton.core.util.kotlin.DispatcherProvider
import java.io.File
import javax.inject.Inject

class UploadAttachments @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val attachmentsRepository: AttachmentsRepository,
    val messageDetailsRepository: MessageDetailsRepository) {

    suspend operator fun invoke(attachmentIds: List<String>, message: Message?, crypto: AddressCrypto) =
        withContext(dispatcherProvider.Io) {
            val attachmentTempFiles: MutableList<File> = ArrayList()

            for (attachmentId in attachmentIds) {
                val attachment = messageDetailsRepository.findAttachmentById(attachmentId) ?: continue
                if (attachment.filePath == null) {
                    continue
                }
                if (attachment.isUploaded) {
                    continue
                }
                if (attachment.isFileExisting.not()) {
                    continue
                }

//                attachmentTempFiles.add(file)
                attachment.setMessage(message) // TODO test set message!
                val result = attachmentsRepository.upload(attachment, crypto)
                if (result is AttachmentsRepository.Result.Failure) {
                    return@withContext Result.Failure(result.error)
                }
            }
//        // upload public key
//        // upload public key
//        if (mailSettings.getAttachPublicKey()) {
//            attachmentsRepository.uploadPublicKey(mUsername, message, crypto)
//        }
//
//        for (file in attachmentTempFiles) {
//            if (file.exists()) {
//                file.delete()
//            }
//        }

            return@withContext Result.Failure("TODO")
        }

    sealed class Result {
        data class Failure(val error: String) : Result()
    }
}
