import {test} from 'node:test';
import assert from 'node:assert/strict';
import {applyEvent} from '../src/protocol.ts';
const state={v:1,epoch:'a',sequence:10,library:[],settings:{revision:4},playback:{position:5},server:{},updates:{}};
test('applies only the next event and preserves other state',()=>{
  const result=applyEvent(state,{v:1,epoch:'a',sequence:11,type:'settings.updated',data:{revision:5}});
  assert.equal(result.state.settings.revision,5);assert.equal(result.state.playback.position,5);assert.equal(result.refresh,false);assert.equal(state.sequence,10);
});
test('ignores stale and duplicate events',()=>assert.deepEqual(applyEvent(state,{v:1,epoch:'a',sequence:9,type:'library.updated',data:['stale']}),{state,refresh:false}));
test('requests a fresh snapshot after a sequence gap',()=>assert.equal(applyEvent(state,{v:1,epoch:'a',sequence:12,type:'settings.updated'}).refresh,true));
test('a restarted server cannot overwrite state with a different epoch',()=>assert.equal(applyEvent(state,{v:1,epoch:'b',sequence:11,type:'library.updated'}).refresh,true));
test('unsupported protocol requires resynchronization',()=>assert.equal(applyEvent(state,{v:2,epoch:'a',sequence:11,type:'library.updated'}).refresh,true));
test('websocket readiness requests a snapshot',()=>assert.equal(applyEvent(state,{v:1,epoch:'a',sequence:10,type:'sync.ready'}).refresh,true));

test('library changes request shared data rather than a second client-owned library',()=>assert.equal(applyEvent(state,{v:1,epoch:'a',sequence:11,type:'library.updated',data:{revision:5}}).refresh,true));
