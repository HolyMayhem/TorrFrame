import { test } from 'node:test';
import assert from 'node:assert/strict';
import { english, translate } from '../src/translations.ts';
test('English and Russian text preserve interpolation and do not alter content', () => {
  assert.equal(translate('Главная', 'en'), 'Home');
  assert.equal(translate('Главная', 'ru'), 'Главная');
  assert.equal(translate('Home', 'ru'), 'Главная');
  assert.equal(translate('{v0} мин', 'en', {v0: 90}), '90 min');
  assert.equal(translate('A movie title', 'en'), 'A movie title');
  assert.equal(translate('Каталог', 'en', {}, 'Catalog'), 'Catalog');
});
test('Every catalogued English translation is nonempty and contains no untranslated Russian', () => {
  for (const [source, value] of Object.entries(english)) {
    assert.ok(value.trim(), source);
    assert.doesNotMatch(value, /[А-Яа-яЁё]/, source);
  }
});
