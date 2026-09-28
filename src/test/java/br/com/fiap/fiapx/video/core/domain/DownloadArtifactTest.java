package br.com.fiap.fiapx.video.core.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DownloadArtifactTest {
    final UUID video=UUID.randomUUID(),owner=UUID.randomUUID();
    final String key="results/"+owner+"/"+video+"/"+UUID.randomUUID()+"/frames.zip";
    @Test void acceptsBoundedPrivateResultOnly() {
        assertEquals(key,new DownloadArtifact(video,owner,"media-test",key,1,"a".repeat(64)).objectKey());
        assertEquals(1073741824L,new DownloadArtifact(video,owner,"media-test",key,1073741824L,"a".repeat(64)).sizeBytes());
        for(String bucket:Arrays.asList(null,"Upper-case")) assertThrows(IllegalArgumentException.class,()->new DownloadArtifact(video,owner,bucket,key,1,"a".repeat(64)));
        for(String wrong:Arrays.asList(null,"originals/foreign",key.replace("frames.zip","../frames.zip"),key.replace(owner.toString(),UUID.randomUUID().toString())))
            assertThrows(IllegalArgumentException.class,()->new DownloadArtifact(video,owner,"media-test",wrong,1,"a".repeat(64)));
        for(long size:new long[]{0,1073741825L}) assertThrows(IllegalArgumentException.class,()->new DownloadArtifact(video,owner,"media-test",key,size,"a".repeat(64)));
        for(String hash:Arrays.asList(null,"no-hash")) assertThrows(IllegalArgumentException.class,()->new DownloadArtifact(video,owner,"media-test",key,1,hash));
    }
}
