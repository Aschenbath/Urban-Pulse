--检测
if(redis.call('get',KEYS[1]) == ARGV[1]) then
    --删除锁
    return redis.call('del',KEYS[1])
end
return 0