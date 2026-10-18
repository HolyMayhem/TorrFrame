import { test } from 'node:test';
import assert from 'node:assert/strict';
import { imageRoutes, publicImageMirrors, orderedImageRoutes, imageRouteSucceeded, imageRouteFailed } from '../src/catalogImages.ts';
const publicRoutes = publicImageMirrors.map(base => base + 't/p/w342/poster.jpg');
const image = 'https://image.tmdb.org/t/p/w342/poster.jpg';
const config = { route: 'Auto', imageMirrors: ['https://one.example/images/', 'https://two.example/'] };
test('healthy direct images and unrelated metadata sources keep their original URLs', () => {
  assert.deepEqual(imageRoutes(image), [image,...publicRoutes]);
  assert.deepEqual(imageRoutes(image, {...config, route:'Direct'}), [image,'https://one.example/images/t/p/w342/poster.jpg','https://two.example/t/p/w342/poster.jpg',...publicRoutes]);
  assert.deepEqual(imageRoutes('https://other.example/poster.jpg', config), ['https://other.example/poster.jpg']);
});
test('automatic images try the origin and then each mirror while proxy mode omits the origin', () => {
  const mirrors = ['https://one.example/images/t/p/w342/poster.jpg','https://two.example/t/p/w342/poster.jpg'];
  assert.deepEqual(imageRoutes(image, config), [image,...mirrors,...publicRoutes]);
  assert.deepEqual(imageRoutes(image, {...config, route:'Proxy'}), [...mirrors,...publicRoutes]);
});
test('image fallbacks reject insecure or credential-bearing bases and have bounded attempts', () => {
  assert.deepEqual(imageRoutes(image, {...config,imageMirrors:['http://insecure/','https://user:secret@host/','https://host/?key=secret']}), [image,...publicRoutes]);
  assert.equal(imageRoutes(image, {...config,imageMirrors:Array.from({length:20},(_,i)=>`https://mirror${i}.example/`)}).length,7);
});

test('image recovery is independent of catalog source', () => {
  for (const source of ['Tmdb','Cub','Auto']) {
    assert.deepEqual(imageRoutes(image, {route:'Direct',source,imageMirrors:[]}), [image,...publicRoutes]);
  }
  assert.deepEqual(imageRoutes(image, {route:'Direct',source:'Tmdb',imageMirrors:[]}), [image,...publicRoutes]);
});

test('successful routes are preferred and failed hosts are excluded temporarily', () => {
  imageRouteSucceeded('https://nl.imagetmdb.com/t/p/w500/a.jpg');
  assert.equal(new URL(orderedImageRoutes(imageRoutes(image))[0]).host, 'nl.imagetmdb.com');
  imageRouteFailed('https://nl.imagetmdb.com/t/p/w500/a.jpg');
  assert.ok(orderedImageRoutes(imageRoutes(image)).every(url => new URL(url).host !== 'nl.imagetmdb.com'));
  assert.deepEqual(orderedImageRoutes(['/relative.jpg']), ['/relative.jpg']);
});
