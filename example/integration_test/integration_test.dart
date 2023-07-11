import 'dart:io' show Platform;

import 'package:flutter/material.dart' show Icons;
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

// ignore: avoid_relative_lib_imports
import '../lib/main.dart' as app;

/// All tests are done in integration tests,
/// since only integration tests can call native code.
// ignore: invalid_annotation_target
@Timeout(Duration(hours: 2))
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  Platform.isAndroid
      ? group('Android Test', () {
          testWidgets('hasAccess()', (tester) async {
            app.main();
            await tester.pumpAndSettle();
            final button = find.byIcon(Icons.question_mark);
            await tester.tap(button);
            expect(tester.takeException(), isNull);
          });

          testWidgets('putVideo()', (tester) async {
            app.main();
            await tester.pumpAndSettle();
            final button = find.byIcon(Icons.video_file);
            await tester.tap(button);
            expect(tester.takeException(), isNull);
          });

          testWidgets('putImage()', (tester) async {
            app.main();
            await tester.pumpAndSettle();
            final button = find.byIcon(Icons.image);
            await tester.tap(button);
            expect(tester.takeException(), isNull);
          });

          testWidgets('putImageBytes()', (tester) async {
            app.main();
            await tester.pumpAndSettle();
            final button = find.byIcon(Icons.image_rounded);
            await tester.tap(button);
            expect(tester.takeException(), isNull);
          });

          testWidgets('open()', (tester) async {
            app.main();
            await tester.pumpAndSettle();
            final button = find.byIcon(Icons.open_in_new);
            await tester.tap(button);
            expect(tester.takeException(), isNull);
          });
        })
      : group('iOS Test', () {
          testWidgets('hasAccess()', (tester) async {
            app.main();
            await tester.pumpAndSettle();
            final button = find.byIcon(Icons.question_mark);
            await tester.tap(button);
            expect(tester.takeException(), isNull);
          });

          testWidgets('open()', (tester) async {
            app.main();
            await tester.pumpAndSettle();
            final button = find.byIcon(Icons.open_in_new);
            await tester.tap(button);
            expect(tester.takeException(), isNull);
          });

          /// Other functions take longer to implement
          /// because of the possibility of interacting with native dialogs.
          /// For more info: https://github.com/flutter/flutter/wiki/Plugin-Tests
        });
}
