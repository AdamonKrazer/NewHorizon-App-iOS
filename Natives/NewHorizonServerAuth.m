#import "NewHorizonServerAuth.h"

#import <Security/Security.h>
#import <objc/runtime.h>

#import "glfw_keycodes.h"
#import "utils.h"

static NSString *const NHServerAuthService =
    @"com.newhorizon.minecraft.ios.server-auth";
static NSString *const NHServerAuthAccount = @"playnewhorizon.com";
static const void *NHServerAuthControllerKey = &NHServerAuthControllerKey;

static NSDictionary *NHServerAuthQuery(void) {
    return @{
        (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
        (__bridge id)kSecAttrService: NHServerAuthService,
        (__bridge id)kSecAttrAccount: NHServerAuthAccount,
    };
}

static BOOL NHServerAuthPasswordIsValid(NSString *password) {
    if (password.length == 0 || password.length > 122) return NO;
    NSCharacterSet *forbidden = [NSCharacterSet whitespaceAndNewlineCharacterSet];
    for (NSUInteger index = 0; index < password.length; index++) {
        unichar character = [password characterAtIndex:index];
        if ([forbidden characterIsMember:character]
                || [[NSCharacterSet controlCharacterSet]
                    characterIsMember:character]
                || (character >= 0xd800 && character <= 0xdfff)
                || character == 0x00a7) {
            return NO;
        }
    }
    return YES;
}

static BOOL NHServerAuthSave(NSString *password) {
    if (!NHServerAuthPasswordIsValid(password)) return NO;
    NSMutableDictionary *query = NHServerAuthQuery().mutableCopy;
    NSData *data = [password dataUsingEncoding:NSUTF8StringEncoding];
    NSDictionary *changes = @{(__bridge id)kSecValueData: data};
    OSStatus status = SecItemUpdate((__bridge CFDictionaryRef)query,
        (__bridge CFDictionaryRef)changes);
    if (status == errSecSuccess) return YES;
    if (status != errSecItemNotFound) return NO;
    query[(__bridge id)kSecValueData] = data;
    query[(__bridge id)kSecAttrAccessible] =
        (__bridge id)kSecAttrAccessibleWhenUnlockedThisDeviceOnly;
    return SecItemAdd((__bridge CFDictionaryRef)query, NULL) == errSecSuccess;
}

static NSString *NHServerAuthLoad(void) {
    NSMutableDictionary *query = NHServerAuthQuery().mutableCopy;
    query[(__bridge id)kSecReturnData] = @YES;
    query[(__bridge id)kSecMatchLimit] = (__bridge id)kSecMatchLimitOne;
    CFTypeRef result = NULL;
    if (SecItemCopyMatching((__bridge CFDictionaryRef)query, &result)
            != errSecSuccess || result == NULL) {
        return nil;
    }
    NSData *data = CFBridgingRelease(result);
    NSString *password = [[NSString alloc] initWithData:data
                                               encoding:NSUTF8StringEncoding];
    return NHServerAuthPasswordIsValid(password) ? password : nil;
}

static void NHServerAuthAlert(
        UIViewController *presenter, NSString *title, NSString *message) {
    UIAlertController *alert = [UIAlertController
        alertControllerWithTitle:title message:message
        preferredStyle:UIAlertControllerStyleAlert];
    [alert addAction:[UIAlertAction actionWithTitle:localize(@"OK", nil)
        style:UIAlertActionStyleDefault handler:nil]];
    [presenter presentViewController:alert animated:YES completion:nil];
}

void NHServerAuthPresentSetup(UIViewController *presenter, BOOL registration) {
    NSString *title = localize(registration
        ? @"new_horizon.server_auth.register_setup"
        : @"new_horizon.server_auth.login_setup", nil);
    UIAlertController *alert = [UIAlertController
        alertControllerWithTitle:title
        message:localize(@"new_horizon.server_auth.password_message", nil)
        preferredStyle:UIAlertControllerStyleAlert];
    [alert addTextFieldWithConfigurationHandler:^(UITextField *field) {
        field.placeholder = localize(@"new_horizon.server_auth.password_hint", nil);
        field.secureTextEntry = YES;
        field.autocorrectionType = UITextAutocorrectionTypeNo;
        field.spellCheckingType = UITextSpellCheckingTypeNo;
        field.textContentType = UITextContentTypeOneTimeCode;
    }];
    [alert addAction:[UIAlertAction
        actionWithTitle:localize(@"Cancel", nil)
        style:UIAlertActionStyleCancel handler:nil]];
    __weak UIAlertController *weakAlert = alert;
    [alert addAction:[UIAlertAction
        actionWithTitle:localize(@"Save", nil)
        style:UIAlertActionStyleDefault handler:^(__unused UIAlertAction *action) {
        NSString *password = weakAlert.textFields.firstObject.text ?: @"";
        weakAlert.textFields.firstObject.text = @"";
        if (!NHServerAuthSave(password)) {
            NHServerAuthAlert(presenter,
                localize(@"Error", nil),
                localize(@"new_horizon.server_auth.invalid", nil));
        }
    }]];
    [presenter presentViewController:alert animated:YES completion:nil];
}

@interface NHServerAuthController : NSObject
@property(nonatomic, weak) UIViewController *presenter;
@property(nonatomic) BOOL sending;
@property(nonatomic) BOOL registration;
@property(nonatomic) NSUInteger commandIndex;
@property(nonatomic) NSUInteger pollCount;
@property(nonatomic) NSMutableString *command;
@property(nonatomic) NSArray<UIButton *> *buttons;
@end

@implementation NHServerAuthController

- (void)sendLogin:(__unused id)sender {
    [self sendCommandForRegistration:NO];
}

- (void)sendRegistration:(__unused id)sender {
    [self sendCommandForRegistration:YES];
}

- (void)sendCommandForRegistration:(BOOL)registration {
    if (self.sending) return;
    if (!isGrabbing) {
        NHServerAuthAlert(self.presenter,
            localize(@"new_horizon.server_auth.game_not_ready_title", nil),
            localize(@"new_horizon.server_auth.game_not_ready", nil));
        return;
    }
    NSString *password = NHServerAuthLoad();
    if (password == nil) {
        NHServerAuthPresentSetup(self.presenter, registration);
        return;
    }

    self.sending = YES;
    self.registration = registration;
    self.commandIndex = 0;
    self.pollCount = 0;
    for (UIButton *button in self.buttons) button.enabled = NO;
    CallbackBridge_nativeSendKey(GLFW_KEY_T, 0, 1, 0);
    CallbackBridge_nativeSendKey(GLFW_KEY_T, 0, 0, 0);
    NSString *prefix = registration ? @"/register " : @"/login ";
    self.command = [registration
        ? [NSString stringWithFormat:@"%@%@ %@", prefix, password, password]
        : [prefix stringByAppendingString:password] mutableCopy];
    [self waitForChat];
}

- (void)waitForChat {
    __weak NHServerAuthController *weakSelf = self;
    if (!isGrabbing) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 32 * NSEC_PER_MSEC),
            dispatch_get_main_queue(), ^{ [weakSelf sendNextCharacter]; });
        return;
    }
    if (++self.pollCount >= 125) {
        [self failWithKey:@"new_horizon.server_auth.chat_open_failed"];
        return;
    }
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 16 * NSEC_PER_MSEC),
        dispatch_get_main_queue(), ^{ [weakSelf waitForChat]; });
}

- (void)sendNextCharacter {
    if (!self.sending) return;
    if (isGrabbing) {
        [self failWithKey:@"new_horizon.server_auth.chat_open_failed"];
        return;
    }
    if (self.commandIndex >= self.command.length) {
        __weak NHServerAuthController *weakSelf = self;
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 48 * NSEC_PER_MSEC),
            dispatch_get_main_queue(), ^{ [weakSelf submitCommand]; });
        return;
    }
    unichar character = [self.command characterAtIndex:self.commandIndex++];
    if (!CallbackBridge_nativeSendChar(character)) {
        [self failWithKey:@"new_horizon.server_auth.input_unavailable"];
        return;
    }
    __weak NHServerAuthController *weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 3 * NSEC_PER_MSEC),
        dispatch_get_main_queue(), ^{ [weakSelf sendNextCharacter]; });
}

- (void)submitCommand {
    if (!self.sending) return;
    if (isGrabbing) {
        [self failWithKey:@"new_horizon.server_auth.chat_open_failed"];
        return;
    }
    CallbackBridge_nativeSendKey(GLFW_KEY_ENTER, 0, 1, 0);
    CallbackBridge_nativeSendKey(GLFW_KEY_ENTER, 0, 0, 0);
    [self wipeCommand];
    self.pollCount = 0;
    [self waitForSubmission];
}

- (void)waitForSubmission {
    if (!self.sending) return;
    if (isGrabbing) {
        NHServerAuthAlert(self.presenter,
            localize(@"new_horizon.server_auth.sent_title", nil),
            localize(self.registration
                ? @"new_horizon.server_auth.register_sent"
                : @"new_horizon.server_auth.login_sent", nil));
        [self finish];
        return;
    }
    if (++self.pollCount >= 125) {
        [self failWithKey:@"new_horizon.server_auth.submit_unconfirmed"];
        return;
    }
    __weak NHServerAuthController *weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 16 * NSEC_PER_MSEC),
        dispatch_get_main_queue(), ^{ [weakSelf waitForSubmission]; });
}

- (void)wipeCommand {
    if (self.command.length > 0) {
        unichar zero = 0;
        NSString *zeroString = [NSString stringWithCharacters:&zero length:1];
        for (NSUInteger index = 0; index < self.command.length; index++) {
            [self.command replaceCharactersInRange:NSMakeRange(index, 1)
                                        withString:zeroString];
        }
    }
    self.command = nil;
    self.commandIndex = 0;
}

- (void)failWithKey:(NSString *)key {
    // Do not leave a partially typed password visible in Minecraft's chat.
    if (!isGrabbing) {
        CallbackBridge_nativeSendKey(GLFW_KEY_ESCAPE, 0, 1, 0);
        CallbackBridge_nativeSendKey(GLFW_KEY_ESCAPE, 0, 0, 0);
    }
    [self wipeCommand];
    NHServerAuthAlert(self.presenter, localize(@"Error", nil), localize(key, nil));
    [self finish];
}

- (void)finish {
    self.sending = NO;
    for (UIButton *button in self.buttons) button.enabled = YES;
}

@end

static UIButton *NHServerAuthButton(NSString *title, id target, SEL action) {
    UIButton *button = [UIButton buttonWithType:UIButtonTypeSystem];
    [button setTitle:title forState:UIControlStateNormal];
    button.titleLabel.font = [UIFont boldSystemFontOfSize:13.0];
    button.backgroundColor = [UIColor colorWithWhite:0.08 alpha:0.82];
    button.tintColor = UIColor.whiteColor;
    button.layer.cornerRadius = 7.0;
    button.contentEdgeInsets = UIEdgeInsetsMake(7, 11, 7, 11);
    [button addTarget:target action:action
        forControlEvents:UIControlEventPrimaryActionTriggered];
    return button;
}

void NHServerAuthInstallGameplayControls(
        UIViewController *presenter, UIView *container) {
    NHServerAuthController *controller = [NHServerAuthController new];
    controller.presenter = presenter;
    objc_setAssociatedObject(presenter, NHServerAuthControllerKey, controller,
        OBJC_ASSOCIATION_RETAIN_NONATOMIC);

    UIButton *login = NHServerAuthButton(
        localize(@"new_horizon.server_auth.login", nil),
        controller, @selector(sendLogin:));
    UIButton *registration = NHServerAuthButton(
        localize(@"new_horizon.server_auth.register", nil),
        controller, @selector(sendRegistration:));
    controller.buttons = @[login, registration];
    UIStackView *stack = [[UIStackView alloc]
        initWithArrangedSubviews:controller.buttons];
    stack.axis = UILayoutConstraintAxisHorizontal;
    stack.spacing = 6.0;
    stack.translatesAutoresizingMaskIntoConstraints = NO;
    [container addSubview:stack];
    [NSLayoutConstraint activateConstraints:@[
        [stack.topAnchor constraintEqualToAnchor:container.safeAreaLayoutGuide.topAnchor
                                         constant:8.0],
        [stack.trailingAnchor constraintEqualToAnchor:container.safeAreaLayoutGuide.trailingAnchor
                                              constant:-8.0],
    ]];
}
