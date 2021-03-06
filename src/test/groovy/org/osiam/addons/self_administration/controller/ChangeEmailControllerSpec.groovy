/*
 * Copyright (C) 2013 tarent AG
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.osiam.addons.self_administration.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.joda.time.Duration
import org.osiam.addons.self_administration.Config
import org.osiam.addons.self_administration.exception.OsiamException
import org.osiam.addons.self_administration.mail.SendEmail
import org.osiam.addons.self_administration.one_time_token.OneTimeToken
import org.osiam.addons.self_administration.template.EmailTemplateRenderer
import org.osiam.addons.self_administration.template.RenderAndSendEmail
import org.osiam.client.OsiamConnector
import org.osiam.client.exception.UnauthorizedException
import org.osiam.client.oauth.AccessToken
import org.osiam.resources.scim.Email
import org.osiam.resources.scim.Extension
import org.osiam.resources.scim.User
import org.springframework.http.HttpStatus
import spock.lang.Specification

import javax.servlet.ServletOutputStream
import javax.servlet.http.HttpServletResponse

class ChangeEmailControllerSpec extends Specification {

    ObjectMapper mapper = new ObjectMapper()

    SendEmail sendMailService = Mock()
    EmailTemplateRenderer emailTemplateRendererService = Mock()
    RenderAndSendEmail renderAndSendEmailService = new RenderAndSendEmail(sendMailService: sendMailService,
            emailTemplateRendererService: emailTemplateRendererService)

    def emailChangeLinkPrefix = 'http://localhost:8080/stuff'
    def emailChangeMailFrom = 'bugs@bunny.com'
    def clientEmailChangeUri = 'http://test'

    def bootStrapLib = 'http://bootstrap'
    def angularLib = 'http://angular'
    def jqueryLib = 'http://jquery'

    OsiamConnector osiamConnector = Mock()
    Config config = new Config(confirmationTokenTimeout: Duration.standardHours(24).millis,
            fromAddress: emailChangeMailFrom,
            clientEmailChangeUri: clientEmailChangeUri,
            emailChangeLinkPrefix: emailChangeLinkPrefix,
            bootStrapLib: bootStrapLib,
            angularLib: angularLib,
            jqueryLib: jqueryLib)

    ChangeEmailController changeEmailController = new ChangeEmailController(
            mapper: mapper,
            renderAndSendEmailService: renderAndSendEmailService,
            osiamConnector: osiamConnector,
            config: config)

    def 'there should be a failure in change email if email template file was not found'() {
        given:
        def authZHeader = 'Bearer ACCESSTOKEN'
        def newEmailValue = 'bam@boom.com'
        User user = new User.Builder().build()

        when:
        changeEmailController.change(authZHeader, newEmailValue)

        then:
        1 * osiamConnector.getMe(_) >> new User.Builder('irrelevant').build()
        1 * osiamConnector.replaceUser(_, _, _) >> user
        1 * emailTemplateRendererService.renderEmailSubject(_, _, _) >> 'subject'
        1 * emailTemplateRendererService.renderEmailBody(_, _, _) >> { throw new OsiamException() }
    }

    def 'there should be a failure in change email if email body not found'() {
        given:
        def authZHeader = 'Bearer ACCESSTOKEN'
        def newEmailValue = 'bam@boom.com'
        User user = new User.Builder().build()

        when:
        changeEmailController.change(authZHeader, newEmailValue)

        then:
        1 * osiamConnector.getMe(_) >> new User.Builder('irrelevant').build()
        1 * osiamConnector.replaceUser(_, _, _) >> user
        1 * emailTemplateRendererService.renderEmailSubject(_, _, _) >> 'subject'
        1 * emailTemplateRendererService.renderEmailBody(_, _, _) >> { throw new OsiamException() }
    }

    def 'there should be a failure in change email if email subject not found'() {
        given:
        def authZHeader = 'Bearer ACCESSTOKEN'
        def newEmailValue = 'bam@boom.com'
        User user = new User.Builder().build()

        when:
        changeEmailController.change(authZHeader, newEmailValue)

        then:
        1 * osiamConnector.getMe(_) >> new User.Builder('irrelevant').build()
        1 * osiamConnector.replaceUser(_, _, _) >> user
        1 * emailTemplateRendererService.renderEmailSubject(_, _, _) >> { throw new OsiamException() }
    }

    def 'change email should generate a confirmation token, save the new email temporarily and send an email'() {
        given:
        def authZHeader = 'Bearer ACCESSTOKEN'
        def newEmailValue = 'bam@boom.com'
        User user = new User.Builder('irrelevant').build()

        def emailContent = 'nine bytes and one placeholder $EMAILCHANGEURL and $BOOTSTRAP and $ANGULAR and $JQUERY'

        when:
        def result = changeEmailController.change(authZHeader, newEmailValue)

        then:
        1 * osiamConnector.getMe(_) >> new User.Builder('irrelevant').build()
        1 * osiamConnector.replaceUser(_, _, _) >> user
        1 * emailTemplateRendererService.renderEmailSubject(_, _, _) >> 'subject'
        1 * emailTemplateRendererService.renderEmailBody(_, _, _) >> emailContent
        1 * sendMailService.sendHTMLMail(_, _, _, _)
        result.getStatusCode() == HttpStatus.OK
    }

    def 'should catch UnauthorizedException and returning response with error message'() {
        given:
        def authZ = 'invalid access token'
        AccessToken accessToken = new AccessToken.Builder('token').build()

        when:
        def result = changeEmailController.change(authZ, 'some@email.de')

        then:
        1 * osiamConnector.getMe(accessToken) >> { throw new UnauthorizedException('unauthorized') }
        result.getBody() == '{\"error\":\"unauthorized\"}'
    }

    def 'confirm email should validate the confirmation token and save the new email value as primary email and send an email'() {
        given:
        def authZHeader = 'abc'
        def userId = 'userId'
        OneTimeToken confirmToken = new OneTimeToken()
        Extension extension = new Extension.Builder(Config.EXTENSION_URN)
                .setField('emailConfirmToken', confirmToken.toString())
                .setField('tempMail', 'my@mail.com').build()
        User user = new User.Builder().addExtension(extension)
                .addEmails([new Email.Builder().setValue('email@example.org').setPrimary(true).build()] as List).build()

        when:
        def result = changeEmailController.confirm(authZHeader, userId, confirmToken.token)

        then:
        1 * osiamConnector.getUser(_, _) >> user
        1 * osiamConnector.replaceUser(userId, _, _) >> user

        result.getStatusCode() == HttpStatus.OK
    }

    def 'confirm email should work with old tokens'() {
        given:
        def authZHeader = 'abc'
        def userId = 'userId'
        def oldConfirmToken = 'irrelevant'

        Extension extension = new Extension.Builder(Config.EXTENSION_URN)
                .setField('emailConfirmToken', oldConfirmToken)
                .setField('tempMail', 'my@mail.com')
                .build()
        User user = new User.Builder()
                .addExtension(extension)
                .addEmails([new Email.Builder().setValue('email@example.org').setPrimary(true).build()] as List).build()

        when:
        def result = changeEmailController.confirm(authZHeader, userId, oldConfirmToken)

        then:
        1 * osiamConnector.getUser(_, _) >> user
        1 * osiamConnector.replaceUser(userId, _, _) >> user

        result.getStatusCode() == HttpStatus.OK
    }

    def 'there should be a failure if confirmation token miss match'() {
        given:
        def authZHeader = 'abc'
        def userId = 'userId'
        def confirmToken = 'confToken'

        Extension extension = new Extension.Builder(Config.EXTENSION_URN)
                .setField('emailConfirmToken', 'wrong token')
                .setField('tempMail', 'my@mail.com').build()
        User user = new User.Builder().addExtension(extension)
                .build()

        when:
        def response = changeEmailController.confirm(authZHeader, userId, confirmToken)

        then:
        1 * osiamConnector.getUser(_, _) >> user
        response.getStatusCode() == HttpStatus.FORBIDDEN
    }

    def 'there should be a failure if the provided confirmation token is empty'() {
        when:
        def result = changeEmailController.confirm('authZ', 'userId', '')

        then:
        result.getStatusCode() == HttpStatus.FORBIDDEN
    }

    def 'the controller should provide a html form for entering the new email address'() {
        given:
        def servletResponseMock = Mock(HttpServletResponse)
        def servletOutputStream = Mock(ServletOutputStream)

        when:
        changeEmailController.index(servletResponseMock)

        then:
        1 * servletResponseMock.getOutputStream() >> servletOutputStream
    }

    def 'there should be a failure if confirmation token is expired'() {
        given:
        def authZHeader = 'abc'
        def userId = 'userId'
        def confirmToken = 'confToken'
        def expiredToken = 'confToken:1'

        Extension extension = new Extension.Builder(Config.EXTENSION_URN)
                .setField('emailConfirmToken', expiredToken)
                .setField('tempMail', 'my@mail.com').build()
        User user = new User.Builder().addExtension(extension)
                .build()

        when:
        def response = changeEmailController.confirm(authZHeader, userId, confirmToken)

        then:
        1 * osiamConnector.getUser(_, _) >> user
        response.getStatusCode() == HttpStatus.FORBIDDEN
    }
}
